/*
 * Copyright 2025 Google LLC
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

package com.google.adk.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.MapDbArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.memory.MapDBMemoryService;
import com.google.adk.plugins.Plugin;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.MapDbSessionService;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The class for the MapDB-backed GenAi runner. */
@SuppressWarnings("deprecation") // Plumbs the deprecated ResumabilityConfig.
public class MapDbRunner extends Runner {

  /** Builder for {@link MapDbRunner}. */
  public static class Builder extends Runner.Builder {
    private App app;
    private BaseAgent agent;
    private String appName;
    private BaseArtifactService artifactService;
    private BaseSessionService sessionService;
    @Nullable private BaseMemoryService memoryService = new InMemoryMemoryService();
    private List<? extends Plugin> plugins = ImmutableList.of();
    @Nullable private EventsCompactionConfig eventsCompactionConfig;
    @Nullable private ContextCacheConfig contextCacheConfig;
    @Nullable private ResumabilityConfig resumabilityConfig;

    @Override
    @CanIgnoreReturnValue
    public Builder app(App app) {
      Preconditions.checkState(this.agent == null, "app() cannot be called when agent() is set.");
      Preconditions.checkState(
          this.plugins.isEmpty(), "app() cannot be called when plugins() is set.");
      this.app = app;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder agent(BaseAgent agent) {
      Preconditions.checkState(this.app == null, "agent() cannot be called when app is set.");
      this.agent = agent;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder appName(String appName) {
      this.appName = appName;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder artifactService(BaseArtifactService artifactService) {
      this.artifactService = artifactService;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder sessionService(BaseSessionService sessionService) {
      this.sessionService = sessionService;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder memoryService(BaseMemoryService memoryService) {
      this.memoryService = memoryService;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder plugins(List<? extends Plugin> plugins) {
      Preconditions.checkState(this.app == null, "plugins() cannot be called when app is set.");
      this.plugins = plugins;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder plugins(Plugin... plugins) {
      Preconditions.checkState(this.app == null, "plugins() cannot be called when app is set.");
      this.plugins = ImmutableList.copyOf(plugins);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder eventsCompactionConfig(EventsCompactionConfig eventsCompactionConfig) {
      this.eventsCompactionConfig = eventsCompactionConfig;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder contextCacheConfig(ContextCacheConfig contextCacheConfig) {
      this.contextCacheConfig = contextCacheConfig;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder resumabilityConfig(ResumabilityConfig resumabilityConfig) {
      this.resumabilityConfig = resumabilityConfig;
      return this;
    }

    @Override
    public MapDbRunner build() {
      BaseAgent buildAgent;
      String buildAppName;
      List<? extends Plugin> buildPlugins;
      EventsCompactionConfig buildEventsCompactionConfig;
      ContextCacheConfig buildContextCacheConfig;
      ResumabilityConfig buildResumabilityConfig;

      if (this.app != null) {
        if (this.agent != null) {
          throw new IllegalStateException("agent() cannot be called when app() is called.");
        }
        if (!this.plugins.isEmpty()) {
          throw new IllegalStateException("plugins() cannot be called when app() is called.");
        }
        buildAgent = this.app.rootAgent();
        buildPlugins = this.app.plugins();
        buildAppName = this.appName == null ? this.app.name() : this.appName;
        buildEventsCompactionConfig =
            this.eventsCompactionConfig != null
                ? this.eventsCompactionConfig
                : this.app.eventsCompactionConfig();
        buildContextCacheConfig =
            this.contextCacheConfig != null
                ? this.contextCacheConfig
                : this.app.contextCacheConfig();
        buildResumabilityConfig =
            this.resumabilityConfig != null
                ? this.resumabilityConfig
                : this.app.resumabilityConfig();
      } else {
        buildAgent = this.agent;
        buildAppName = this.appName;
        buildPlugins = this.plugins;
        buildEventsCompactionConfig = this.eventsCompactionConfig;
        buildContextCacheConfig = this.contextCacheConfig;
        buildResumabilityConfig = this.resumabilityConfig;
      }

      if (buildAgent == null) {
        throw new IllegalStateException("Agent must be provided via app() or agent().");
      }
      if (buildAppName == null) {
        throw new IllegalStateException("App name must be provided via app() or appName().");
      }

      try {
        BaseArtifactService buildArtifactService =
            this.artifactService != null
                ? this.artifactService
                : new MapDbArtifactService(buildAppName + "_ART");
        BaseSessionService buildSessionService =
            this.sessionService != null
                ? this.sessionService
                : new MapDbSessionService(buildAppName);

        return new MapDbRunner(
            buildAgent,
            buildAppName,
            buildArtifactService,
            buildSessionService,
            this.memoryService,
            buildPlugins,
            buildEventsCompactionConfig,
            buildContextCacheConfig,
            buildResumabilityConfig);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public MapDbRunner(BaseAgent agent) throws IOException {
    this(agent, /* appName= */ agent.name());
  }

  public MapDbRunner(BaseAgent agent, String appName) throws IOException {
    this(agent, appName, new InMemoryMemoryService());
  }

  public MapDbRunner(BaseAgent agent, String appName, MapDBMemoryService mapDBMemoryService)
      throws IOException {
    this(agent, appName, (BaseMemoryService) mapDBMemoryService);
  }

  public MapDbRunner(BaseAgent agent, String appName, BaseMemoryService memoryService)
      throws IOException {
    this(agent, appName, memoryService, ImmutableList.of());
  }

  public MapDbRunner(BaseAgent agent, String appName, List<? extends Plugin> plugins)
      throws IOException {
    this(agent, appName, new InMemoryMemoryService(), plugins);
  }

  public MapDbRunner(
      BaseAgent agent,
      String appName,
      BaseMemoryService memoryService,
      List<? extends Plugin> plugins)
      throws IOException {
    this(
        agent,
        appName,
        new MapDbArtifactService(appName + "_ART"),
        new MapDbSessionService(appName),
        memoryService,
        plugins,
        /* eventsCompactionConfig= */ null,
        /* contextCacheConfig= */ null,
        /* resumabilityConfig= */ null);
  }

  public MapDbRunner(
      BaseAgent agent,
      String appName,
      BaseMemoryService memoryService,
      List<? extends Plugin> plugins,
      @Nullable EventsCompactionConfig eventsCompactionConfig,
      @Nullable ContextCacheConfig contextCacheConfig,
      @Nullable ResumabilityConfig resumabilityConfig)
      throws IOException {
    this(
        agent,
        appName,
        new MapDbArtifactService(appName + "_ART"),
        new MapDbSessionService(appName),
        memoryService,
        plugins,
        eventsCompactionConfig,
        contextCacheConfig,
        resumabilityConfig);
  }

  public MapDbRunner(App app) throws IOException {
    this(app, new InMemoryMemoryService());
  }

  public MapDbRunner(App app, BaseMemoryService memoryService) throws IOException {
    this(
        app.rootAgent(),
        app.name(),
        new MapDbArtifactService(app.name() + "_ART"),
        new MapDbSessionService(app.name()),
        memoryService,
        app.plugins(),
        app.eventsCompactionConfig(),
        app.contextCacheConfig(),
        app.resumabilityConfig());
  }

  public MapDbRunner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      @Nullable BaseMemoryService memoryService,
      List<? extends Plugin> plugins,
      @Nullable EventsCompactionConfig eventsCompactionConfig,
      @Nullable ContextCacheConfig contextCacheConfig,
      @Nullable ResumabilityConfig resumabilityConfig) {
    super(
        agent,
        appName,
        artifactService,
        sessionService,
        memoryService,
        plugins,
        eventsCompactionConfig,
        contextCacheConfig,
        resumabilityConfig);
  }

  /**
   * Exports all session and state data to a JSON file.
   *
   * @param path The path to the output JSON file.
   * @throws IOException If an I/O error occurs during writing.
   */
  public void exportToJson(Path path) throws IOException {
    if (sessionService() instanceof MapDbSessionService) {
      MapDbSessionService service = (MapDbSessionService) sessionService();
      Map<String, Object> data = service.getAllData();
      ObjectMapper mapper = new ObjectMapper();
      mapper.enable(SerializationFeature.INDENT_OUTPUT);
      mapper.findAndRegisterModules();
      mapper.writeValue(path.toFile(), data);
    } else {
      throw new IllegalStateException("Session service is not an instance of MapDbSessionService");
    }
  }
}
