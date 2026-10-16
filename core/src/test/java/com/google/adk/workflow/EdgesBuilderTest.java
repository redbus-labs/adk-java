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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EdgesBuilderTest {

  private final EdgesBuilder builder = new EdgesBuilder();
  private final Node a = new StubNode("a");
  private final Node b = new StubNode("b");
  private final Node c = new StubNode("c");
  private final Node d = new StubNode("d");
  private final JoinNode join = JoinNode.builder().name("join").build();

  @Test
  public void build_withoutEdges_returnsAnEmptyList() {
    assertThat(builder.build()).isEmpty();
  }

  @Test
  public void fromStart_chainOfThens_connectsEachNodeToTheNext() {
    builder.fromStart().then(a).then(b);

    assertThat(builder.build())
        .containsExactly(new Edge(Start.INSTANCE, a), new Edge(a, b))
        .inOrder();
  }

  @Test
  public void from_startsTheChainAtTheNode() {
    builder.from(a).then(b);

    assertThat(builder.build()).containsExactly(new Edge(a, b));
  }

  @Test
  public void from_rejectsNull() {
    assertThrows(NullPointerException.class, () -> builder.from((Node) null));
  }

  @Test
  public void chain_severalNodes_connectsEachNodeToTheNext() {
    builder.fromStart().chain(a, b, c);

    assertThat(builder.build())
        .containsExactly(new Edge(Start.INSTANCE, a), new Edge(a, b), new Edge(b, c))
        .inOrder();
  }

  @Test
  public void chain_twoNodes_continuesFromTheLastOne() {
    builder.from(a).chain(b, c).then(d);

    assertThat(builder.build())
        .containsExactly(new Edge(a, b), new Edge(b, c), new Edge(c, d))
        .inOrder();
  }

  @Test
  public void chain_withANullNode_throwsAndAddsNoEdges() {
    EdgesBuilder.EdgeChain chain = builder.from(a);

    assertThrows(NullPointerException.class, () -> chain.chain(b, c, (Node) null));
    assertThat(builder.build()).isEmpty();
  }

  @Test
  public void then_collection_fansOutToEach() {
    builder.fromStart().then(a).then(ImmutableList.of(b, c, d));

    assertThat(builder.build())
        .containsExactly(
            new Edge(Start.INSTANCE, a), new Edge(a, b), new Edge(a, c), new Edge(a, d))
        .inOrder();
  }

  @Test
  public void then_emptyCollection_addsNoEdges() {
    builder.fromStart().then(a).then(ImmutableList.of());

    assertThat(builder.build()).containsExactly(new Edge(Start.INSTANCE, a));
  }

  @Test
  public void then_collectionWithANull_throwsAndAddsNoEdges() {
    EdgesBuilder.EdgeChain chain = builder.from(a);

    assertThrows(NullPointerException.class, () -> chain.then(Arrays.asList(b, null)));
    assertThat(builder.build()).isEmpty();
  }

  @Test
  public void then_collection_continuesAsAGroupThatJoins() {
    builder.fromStart().then(ImmutableList.of(b, c)).joinTo(join).then(d);

    assertThat(builder.build())
        .containsExactly(
            new Edge(Start.INSTANCE, b),
            new Edge(Start.INSTANCE, c),
            new Edge(b, join),
            new Edge(c, join),
            new Edge(join, d))
        .inOrder();
  }

  @Test
  public void then_severalNodes_fansOutToEachAndContinuesAsAGroup() {
    builder.from(a).then(b, c, d).joinTo(join);

    assertThat(builder.build())
        .containsExactly(
            new Edge(a, b),
            new Edge(a, c),
            new Edge(a, d),
            new Edge(b, join),
            new Edge(c, join),
            new Edge(d, join))
        .inOrder();
  }

  @Test
  public void then_severalNodesWithANull_throwsAndAddsNoEdges() {
    EdgesBuilder.EdgeChain chain = builder.from(a);

    assertThrows(NullPointerException.class, () -> chain.then(b, c, (Node) null));
    assertThat(builder.build()).isEmpty();
  }

  @Test
  public void from_collection_joinsWithoutAFanOut() {
    builder.from(ImmutableList.of(b, c)).joinTo(join).then(d);

    assertThat(builder.build())
        .containsExactly(new Edge(b, join), new Edge(c, join), new Edge(join, d))
        .inOrder();
  }

  @Test
  public void from_collectionWithANull_throws() {
    assertThrows(NullPointerException.class, () -> builder.from(Arrays.asList(b, null)));
  }

  @Test
  public void from_severalNodes_joinsWithoutAFanOut() {
    builder.from(a, b, c).joinTo(join).then(d);

    assertThat(builder.build())
        .containsExactly(new Edge(a, join), new Edge(b, join), new Edge(c, join), new Edge(join, d))
        .inOrder();
  }

  @Test
  public void from_severalNodesWithANull_throws() {
    assertThrows(NullPointerException.class, () -> builder.from(a, b, (Node) null));
  }

  @Test
  public void joinTo_emptyGroup_addsNoEdgesAndContinuesFromTheJoin() {
    builder.from(ImmutableList.of()).joinTo(join).then(d);

    assertThat(builder.build()).containsExactly(new Edge(join, d));
  }

  @Test
  public void joinTo_rejectsNull() {
    // An empty group adds no edge that would reject the null join on its own.
    EdgesBuilder.NodeGroupChain group = builder.from(ImmutableList.of());

    assertThrows(NullPointerException.class, () -> group.joinTo(null));
  }

  @Test
  public void route_everyRouteKind_routesFromTheCurrentNodeToEachTarget() {
    ImmutableList<Route> anyRoute = ImmutableList.of(new Route.Tag("x"), new Route.Tag("y"));

    builder
        .from(a)
        .route(
            r -> {
              r.on("tag").then(b, c);
              r.on(true).then(b, c);
              r.on(new Route.Num(7)).then(b, c);
              r.on(r.anyOf("x", "y")).then(b, c);
            });

    assertThat(builder.build())
        .containsExactly(
            new Edge(a, b, new Route.Tag("tag")),
            new Edge(a, c, new Route.Tag("tag")),
            new Edge(a, b, new Route.Flag(true)),
            new Edge(a, c, new Route.Flag(true)),
            new Edge(a, b, new Route.Num(7)),
            new Edge(a, c, new Route.Num(7)),
            new Edge(a, b, anyRoute),
            new Edge(a, c, anyRoute))
        .inOrder();
  }

  @Test
  public void route_otherwiseWithSeveralTargets_addsADefaultEdgeToEach() {
    builder
        .from(a)
        .route(
            r -> {
              r.on("x").then(b);
              r.otherwise(b, c, d);
            });

    assertThat(builder.build())
        .containsExactly(
            new Edge(a, b, new Route.Tag("x")),
            new Edge(a, b, Route.Default.INSTANCE),
            new Edge(a, c, Route.Default.INSTANCE),
            new Edge(a, d, Route.Default.INSTANCE))
        .inOrder();
  }

  @Test
  public void route_emptyRoutingMap_throws() {
    EdgesBuilder.EdgeChain chain = builder.from(a);

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> chain.route(r -> {}));

    assertThat(e).hasMessageThat().contains("routing map of node 'a'");
  }

  @Test
  public void route_entryWithoutATarget_throwsAndAddsNoEdges() {
    EdgesBuilder.EdgeChain chain = builder.from(a);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                chain.route(
                    r -> {
                      r.on("x").then(b);
                      EdgesBuilder.RouteMapEntry unused = r.on("y");
                    }));

    assertThat(e).hasMessageThat().contains("routing map of node 'a' has no target");
    assertThat(builder.build()).isEmpty();
  }

  @Test
  public void route_routeMapDefinitionThatThrows_addsNoEdges() {
    EdgesBuilder.EdgeChain chain = builder.from(a);

    assertThrows(
        NullPointerException.class,
        () ->
            chain.route(
                r -> {
                  r.on("ok").then(b);
                  r.on("bad").then(c, (Node) null);
                }));
    assertThat(builder.build()).isEmpty();
  }

  @Test
  public void route_entryStartedAfterTheRouteReturns_throws() {
    AtomicReference<EdgesBuilder.RouteMap> escaped = new AtomicReference<>();
    builder
        .from(a)
        .route(
            r -> {
              escaped.set(r);
              r.on("x").then(b);
            });

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> escaped.get().on("late"));

    assertThat(e).hasMessageThat().contains("routing map of node 'a' was already built");
    assertThat(builder.build()).containsExactly(new Edge(a, b, new Route.Tag("x")));
  }

  @Test
  public void route_entryCompletedAfterTheRouteReturns_throws() {
    AtomicReference<EdgesBuilder.RouteMapEntry> escaped = new AtomicReference<>();
    builder
        .from(a)
        .route(
            r -> {
              escaped.set(r.on("x"));
              escaped.get().then(b);
            });

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> escaped.get().then(c));

    assertThat(e).hasMessageThat().contains("routing map of node 'a' was already built");
    assertThat(builder.build()).containsExactly(new Edge(a, b, new Route.Tag("x")));
  }

  @Test
  public void anyOf_everyOverload_dropsRepeatedRoutes() {
    EdgesBuilder.RouteMap r = new EdgesBuilder.RouteMap(a);
    Route.Tag p = new Route.Tag("p");
    Route.Num seven = new Route.Num(7);

    assertThat(r.anyOf("x", "y", "z", "x").routes())
        .containsExactly(new Route.Tag("x"), new Route.Tag("y"), new Route.Tag("z"))
        .inOrder();
    assertThat(r.anyOf(p, new Route.Flag(true), seven, p).routes())
        .containsExactly(p, new Route.Flag(true), seven)
        .inOrder();
  }

  @Test
  public void build_documentedExample_buildsItsGraph() {
    Node classify = new StubNode("classifier");
    Node category = new StubNode("category");
    Node billing = new StubNode("billing");
    Node summarize = new StubNode("summary");

    builder
        .fromStart()
        .chain(classify, category)
        .route(
            r -> {
              r.on("billing").then(billing);
              r.otherwise(summarize);
            });
    builder.from(billing).then(summarize);

    assertThat(builder.build())
        .containsExactly(
            new Edge(Start.INSTANCE, classify),
            new Edge(classify, category),
            new Edge(category, billing, new Route.Tag("billing")),
            new Edge(category, summarize, Route.Default.INSTANCE),
            new Edge(billing, summarize))
        .inOrder();
  }
}
