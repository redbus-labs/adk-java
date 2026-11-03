/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.workflow;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.annotations.Experimental;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * A builder for the edges of a workflow graph, declared as chains of calls that start at {@link
 * #from} or {@link #fromStart}. Create one with {@code new EdgesBuilder()} and call {@link #build},
 * or let {@link Workflow.Builder#edges(Consumer)} do both. A node is identified by its instance, so
 * create each node once.
 *
 * <pre>{@code
 * Workflow support =
 *     Workflow.builder()
 *         .name("support_triage")
 *         .edges(
 *             g -> {
 *               g.fromStart()
 *                   .chain(classify, category)
 *                   .route(
 *                       r -> {
 *                         r.on("billing").then(billing);
 *                         r.otherwise(summarize);
 *                       });
 *               g.from(billing).then(summarize);
 *             })
 *         .build();
 * }</pre>
 */
@Experimental
public final class EdgesBuilder {

  private final List<Edge> edges = new ArrayList<>();

  /** Starts a chain of edges at {@code node}: {@code g.from(a).then(b).then(c)}. */
  public EdgeChain from(Node node) {
    return new EdgeChain(this, checkNotNull(node));
  }

  /**
   * Starts a chain at {@code nodes} (such as a computed list) so they can join without a fan-out,
   * like {@link #from(Node, Node, Node...)}.
   */
  public NodeGroupChain from(Collection<? extends Node> nodes) {
    return new NodeGroupChain(this, ImmutableList.copyOf(nodes));
  }

  /**
   * Starts a chain at {@code first}, {@code second} and each node in {@code rest} so they can join
   * without a fan-out: {@code g.from(a, b).joinTo(join)}.
   */
  public NodeGroupChain from(Node first, Node second, Node... rest) {
    return from(Lists.asList(first, second, rest));
  }

  /** Starts a chain of edges at {@link Start}: {@code g.fromStart().then(a)}. */
  public EdgeChain fromStart() {
    return from(Start.INSTANCE);
  }

  /** Returns the edges added so far, in the order they were added. */
  public ImmutableList<Edge> build() {
    return ImmutableList.copyOf(edges);
  }

  void add(Edge edge) {
    edges.add(edge);
  }

  void addAll(List<Edge> newEdges) {
    edges.addAll(newEdges);
  }

  /** Returns an edge from {@code from} to each of {@code targets}, failing if a target is null. */
  static ImmutableList<Edge> edgesTo(
      Node from, Collection<? extends Node> targets, ImmutableList<Route> routes) {
    return targets.stream().map(to -> new Edge(from, to, routes)).collect(toImmutableList());
  }

  /**
   * A chain of edges positioned at a node, started by {@link EdgesBuilder#from(Node)}, {@link
   * EdgesBuilder#fromStart} or {@link NodeGroupChain#joinTo}. {@link #then(Node)} and {@link
   * #chain} add edges and continue from the last node they connect, a fan-out continues from its
   * targets as a group, and a routing map ends the chain.
   */
  @Experimental
  public static final class EdgeChain {

    private final EdgesBuilder builder;
    private final Node node;

    EdgeChain(EdgesBuilder builder, Node node) {
      this.builder = builder;
      this.node = node;
    }

    /** Connects the current node to {@code next} and continues from {@code next}. */
    @CanIgnoreReturnValue
    public EdgeChain then(Node next) {
      builder.add(new Edge(node, next));
      return new EdgeChain(builder, next);
    }

    /**
     * Fans out by connecting the current node to each of {@code targets} (such as a computed list)
     * so they run concurrently, and continues from them as a group that {@link
     * NodeGroupChain#joinTo} can join. An empty collection adds no edges.
     */
    @CanIgnoreReturnValue
    public NodeGroupChain then(Collection<? extends Node> targets) {
      ImmutableList<Node> nodes = ImmutableList.copyOf(targets);
      builder.addAll(edgesTo(node, nodes, ImmutableList.of()));
      return new NodeGroupChain(builder, nodes);
    }

    /**
     * Fans out by connecting the current node to {@code first}, {@code second} and each node in
     * {@code rest}, like {@link #then(Collection)}: {@code g.from(a).then(b, c).joinTo(join)}.
     */
    @CanIgnoreReturnValue
    public NodeGroupChain then(Node first, Node second, Node... rest) {
      return then(Lists.asList(first, second, rest));
    }

    /**
     * Connects the current node to {@code first}, {@code second} and each node in {@code rest} in
     * order, and continues from the last one: {@code g.fromStart().chain(a, b)} is short for {@code
     * g.fromStart().then(a).then(b)}.
     */
    @CanIgnoreReturnValue
    public EdgeChain chain(Node first, Node second, Node... rest) {
      // Copying rejects a null node before any edge is added.
      ImmutableList<Node> nodes = ImmutableList.copyOf(Lists.asList(first, second, rest));
      EdgeChain chain = this;
      for (Node next : nodes) {
        chain = chain.then(next);
      }
      return chain;
    }

    /**
     * Adds the current node's routing map, which {@code routeMapDefinition} fills, and ends the
     * chain.
     *
     * @throws IllegalArgumentException if the routing map has no entries
     * @throws IllegalStateException if an entry started by {@link RouteMap#on} has no target
     */
    public void route(Consumer<? super RouteMap> routeMapDefinition) {
      RouteMap routeMap = new RouteMap(node);
      routeMapDefinition.accept(routeMap);
      ImmutableList<Edge> routeEdges = routeMap.build();
      checkArgument(
          !routeEdges.isEmpty(),
          "The routing map of node '%s' needs at least one entry.",
          node.name());
      builder.addAll(routeEdges);
    }
  }

  /**
   * A chain of edges positioned at a node group from a fan-out, {@link EdgesBuilder#from(Node,
   * Node, Node...)}, or {@link EdgesBuilder#from(Collection)}, which {@link #joinTo} connects to a
   * {@link JoinNode}.
   */
  @Experimental
  public static final class NodeGroupChain {

    private final EdgesBuilder builder;
    private final ImmutableList<Node> nodes;

    NodeGroupChain(EdgesBuilder builder, ImmutableList<Node> nodes) {
      this.builder = builder;
      this.nodes = nodes;
    }

    /**
     * Connects each node in the group to {@code join}, which runs once all of them complete, and
     * continues from {@code join}.
     */
    @CanIgnoreReturnValue
    public EdgeChain joinTo(JoinNode join) {
      checkNotNull(join);
      for (Node node : nodes) {
        builder.add(new Edge(node, join));
      }
      return new EdgeChain(builder, join);
    }
  }

  /**
   * A routing map configured in {@link EdgeChain#route} that maps each route the source node may
   * emit to the nodes it leads to, written {@code r.on("billing").then(billing)}. Mapping a route
   * to several nodes fans out on that route.
   */
  @Experimental
  public static final class RouteMap {

    private final Node from;
    private final ImmutableList.Builder<Edge> edges = ImmutableList.builder();
    private final List<RouteMapEntry> pendingEntries = new ArrayList<>();
    private boolean built = false;

    RouteMap(Node from) {
      this.from = from;
    }

    /**
     * Starts an entry for {@code route} as a {@link Route.Tag}; {@link RouteMapEntry#then}
     * completes it.
     */
    public RouteMapEntry on(String route) {
      return on(new Route.Tag(route));
    }

    /**
     * Starts an entry for {@code route} as a {@link Route.Flag}; {@link RouteMapEntry#then}
     * completes it.
     */
    public RouteMapEntry on(boolean route) {
      return on(new Route.Flag(route));
    }

    /**
     * Starts an entry for {@code route}, such as a {@link Route.Num}; {@link RouteMapEntry#then}
     * completes it.
     */
    public RouteMapEntry on(Route route) {
      return start(ImmutableList.of(route));
    }

    /**
     * Starts an entry whose edges fire once when the source node emits any of {@code routes}, built
     * with {@link #anyOf}; {@link RouteMapEntry#then} completes it.
     */
    public RouteMapEntry on(RouteSet routes) {
      return start(routes.routes());
    }

    /**
     * Leads to each target when no other route of the source node matches, including when it emits
     * none.
     */
    public void otherwise(Node first, Node... rest) {
      add(ImmutableList.of(Route.Default.INSTANCE), Lists.asList(first, rest));
    }

    /** Groups string routes as {@link Route.Tag}s for {@link #on(RouteSet)}. */
    public RouteSet anyOf(String first, String second, String... rest) {
      return new RouteSet(Lists.transform(Lists.asList(first, second, rest), Route.Tag::new));
    }

    /** Groups routes, such as {@link Route.Num}s, for {@link #on(RouteSet)}. */
    public RouteSet anyOf(Route first, Route second, Route... rest) {
      return new RouteSet(Lists.asList(first, second, rest));
    }

    /**
     * Returns the map's edges and closes the map, so an entry started or completed after {@link
     * EdgeChain#route} returns fails instead of being lost.
     *
     * @throws IllegalStateException if an entry started by {@code on} has no target
     */
    ImmutableList<Edge> build() {
      built = true;
      checkState(
          pendingEntries.isEmpty(),
          "on(...) in the routing map of node '%s' has no target; complete it with then(node).",
          from.name());
      return edges.build();
    }

    /** Adds an edge for {@code routes} to each of {@code targets} and completes {@code entry}. */
    void complete(RouteMapEntry entry, ImmutableList<Route> routes, List<Node> targets) {
      add(routes, targets);
      pendingEntries.remove(entry);
    }

    private RouteMapEntry start(ImmutableList<Route> routes) {
      checkNotBuilt();
      RouteMapEntry entry = new RouteMapEntry(this, routes);
      pendingEntries.add(entry);
      return entry;
    }

    private void add(ImmutableList<Route> routes, List<Node> targets) {
      checkNotBuilt();
      edges.addAll(edgesTo(from, targets, routes));
    }

    private void checkNotBuilt() {
      checkState(
          !built,
          "The routing map of node '%s' was already built; add its entries inside route(...).",
          from.name());
    }
  }

  /**
   * A routing-map entry started by {@link RouteMap#on} whose routes have no target yet; {@link
   * #then} completes it.
   */
  @Experimental
  public static final class RouteMapEntry {

    private final RouteMap routeMap;
    private final ImmutableList<Route> routes;

    RouteMapEntry(RouteMap routeMap, ImmutableList<Route> routes) {
      this.routeMap = routeMap;
      this.routes = routes;
    }

    /**
     * Leads to {@code first} and each node in {@code rest}, with one edge to each, when the source
     * node emits any of this entry's routes.
     */
    public void then(Node first, Node... rest) {
      routeMap.complete(this, routes, Lists.asList(first, rest));
    }
  }

  /**
   * A set of routes that select a single edge, which fires once when its source emits any of them;
   * separate edges to the same target would fire once each. {@link RouteMap#anyOf} builds one and
   * drops duplicate routes.
   */
  @Experimental
  public static final class RouteSet {

    private final ImmutableList<Route> routes;

    RouteSet(List<? extends Route> routes) {
      this.routes = ImmutableSet.<Route>copyOf(routes).asList();
    }

    ImmutableList<Route> routes() {
      return routes;
    }
  }
}
