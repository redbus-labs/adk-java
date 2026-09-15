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

import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.artifacts.MapDbArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.memory.MapDBMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.MapDbManager;
import com.google.adk.sessions.MapDbSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SuppressWarnings("deprecation")
public final class MapDbRunnerTest {

  private static final List<Object> activeReferences =
      Collections.synchronizedList(new ArrayList<>());

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private TestLlm testLlm;
  private LlmAgent agent;
  private String appName;

  private static Content createContent(String text) {
    return Content.fromParts(Part.fromText(text));
  }

  private BasePlugin mockPlugin(String name) {
    BasePlugin plugin = mock(BasePlugin.class, CALLS_REAL_METHODS);
    when(plugin.getName()).thenReturn(name);
    return plugin;
  }

  private <T> T keep(T ref) {
    activeReferences.add(ref);
    return ref;
  }

  @Before
  public void setUp() {
    this.testLlm = createTestLlm(createLlmResponse(createContent("Hello from MapDbRunner")));
    this.agent = createTestAgentBuilder(testLlm).name("testAgent").build();
    this.appName = "app_" + UUID.randomUUID().toString().replace("-", "_");
  }

  @After
  public void tearDown() {
    // Keep resources alive until class tearDown so finalizers don't close singleton DB mid-suite.
  }

  @AfterClass
  public static void tearDownClass() {
    activeReferences.clear();
    MapDbManager.closeDb();
  }

  @Test
  public void builder_withAgentAndAppName() {
    MapDbRunner runner = keep(MapDbRunner.builder().agent(agent).appName(appName).build());

    assertThat(runner.agent()).isSameInstanceAs(agent);
    assertThat(runner.appName()).isEqualTo(appName);
    assertThat(runner.sessionService()).isInstanceOf(MapDbSessionService.class);
    assertThat(runner.artifactService()).isInstanceOf(MapDbArtifactService.class);
    assertThat(runner.memoryService()).isInstanceOf(InMemoryMemoryService.class);
  }

  @Test
  public void builder_withApp() {
    Plugin plugin = mockPlugin("testPlugin");
    EventsCompactionConfig compactionConfig = new EventsCompactionConfig(5, 2);
    ContextCacheConfig contextCacheConfig = new ContextCacheConfig(10, Duration.ofSeconds(1800), 0);
    ResumabilityConfig resumabilityConfig = ResumabilityConfig.builder().build();

    App app =
        App.builder()
            .name(appName)
            .rootAgent(agent)
            .plugins(ImmutableList.of(plugin))
            .eventsCompactionConfig(compactionConfig)
            .contextCacheConfig(contextCacheConfig)
            .resumabilityConfig(resumabilityConfig)
            .build();

    MapDbRunner runner = keep(MapDbRunner.builder().app(app).build());

    assertThat(runner.agent()).isSameInstanceAs(agent);
    assertThat(runner.appName()).isEqualTo(appName);
    assertThat(runner.sessionService()).isInstanceOf(MapDbSessionService.class);
    assertThat(runner.artifactService()).isInstanceOf(MapDbArtifactService.class);
    assertThat(runner.pluginManager().getPlugins()).containsExactly(plugin);
  }

  @Test
  public void builder_withAppAndOverriddenAppName() {
    String overrideName = "override_" + UUID.randomUUID().toString().replace("-", "_");

    App app = App.builder().name(appName).rootAgent(agent).build();

    MapDbRunner runner = keep(MapDbRunner.builder().app(app).appName(overrideName).build());

    assertThat(runner.appName()).isEqualTo(overrideName);
    assertThat(runner.agent()).isSameInstanceAs(agent);
  }

  @Test
  public void builder_withCustomServices() {
    BaseArtifactService customArtifactService = new InMemoryArtifactService();
    BaseSessionService customSessionService = new InMemorySessionService();
    BaseMemoryService customMemoryService = mock(BaseMemoryService.class);

    MapDbRunner runner =
        keep(
            MapDbRunner.builder()
                .agent(agent)
                .appName("customApp")
                .artifactService(customArtifactService)
                .sessionService(customSessionService)
                .memoryService(customMemoryService)
                .build());

    assertThat(runner.artifactService()).isSameInstanceAs(customArtifactService);
    assertThat(runner.sessionService()).isSameInstanceAs(customSessionService);
    assertThat(runner.memoryService()).isSameInstanceAs(customMemoryService);
  }

  @Test
  public void builder_withPluginsListAndVarargs() {
    Plugin plugin1 = mockPlugin("plugin1");
    Plugin plugin2 = mockPlugin("plugin2");

    MapDbRunner runner1 =
        keep(
            MapDbRunner.builder()
                .agent(agent)
                .appName(appName)
                .plugins(ImmutableList.of(plugin1, plugin2))
                .build());
    assertThat(runner1.pluginManager().getPlugins()).containsExactly(plugin1, plugin2).inOrder();

    String appName2 = "app2_" + UUID.randomUUID().toString().replace("-", "_");
    MapDbRunner runner2 =
        keep(
            MapDbRunner.builder().agent(agent).appName(appName2).plugins(plugin1, plugin2).build());
    assertThat(runner2.pluginManager().getPlugins()).containsExactly(plugin1, plugin2).inOrder();
  }

  @Test
  public void builder_withConfigs() {
    EventsCompactionConfig compactionConfig = new EventsCompactionConfig(10, 3);
    ContextCacheConfig contextCacheConfig = new ContextCacheConfig();
    ResumabilityConfig resumabilityConfig = ResumabilityConfig.builder().build();

    MapDbRunner runner =
        keep(
            MapDbRunner.builder()
                .agent(agent)
                .appName(appName)
                .eventsCompactionConfig(compactionConfig)
                .contextCacheConfig(contextCacheConfig)
                .resumabilityConfig(resumabilityConfig)
                .build());

    assertThat(runner.agent()).isSameInstanceAs(agent);
    assertThat(runner.appName()).isEqualTo(appName);
  }

  @Test
  public void builder_throwsWhenAgentAndAppBothSet() {
    App app = App.builder().name("app").rootAgent(agent).build();

    assertThrows(IllegalStateException.class, () -> MapDbRunner.builder().agent(agent).app(app));

    assertThrows(IllegalStateException.class, () -> MapDbRunner.builder().app(app).agent(agent));
  }

  @Test
  public void builder_throwsWhenPluginsAndAppBothSet() {
    Plugin plugin = mockPlugin("plugin");
    App app = App.builder().name("app").rootAgent(agent).build();

    assertThrows(
        IllegalStateException.class,
        () -> MapDbRunner.builder().plugins(ImmutableList.of(plugin)).app(app));

    assertThrows(IllegalStateException.class, () -> MapDbRunner.builder().plugins(plugin).app(app));

    assertThrows(
        IllegalStateException.class,
        () -> MapDbRunner.builder().app(app).plugins(ImmutableList.of(plugin)));

    assertThrows(IllegalStateException.class, () -> MapDbRunner.builder().app(app).plugins(plugin));
  }

  @Test
  public void builder_throwsWhenMissingAgentOrApp() {
    assertThrows(IllegalStateException.class, () -> MapDbRunner.builder().appName("app").build());
  }

  @Test
  public void builder_throwsWhenMissingAppNameOrApp() {
    assertThrows(IllegalStateException.class, () -> MapDbRunner.builder().agent(agent).build());
  }

  @Test
  public void constructors() throws IOException {
    // constructor(BaseAgent)
    MapDbRunner runner1 = keep(new MapDbRunner(agent));
    assertThat(runner1.agent()).isSameInstanceAs(agent);
    assertThat(runner1.appName()).isEqualTo(agent.name());

    // constructor(BaseAgent, String)
    String app2 = "app_const_2";
    MapDbRunner runner2 = keep(new MapDbRunner(agent, app2));
    assertThat(runner2.appName()).isEqualTo(app2);
    assertThat(runner2.memoryService()).isInstanceOf(InMemoryMemoryService.class);

    // constructor(BaseAgent, String, BaseMemoryService)
    String app3 = "app_const_3";
    BaseMemoryService baseMemoryService = mock(BaseMemoryService.class);
    MapDbRunner runner3 = keep(new MapDbRunner(agent, app3, baseMemoryService));
    assertThat(runner3.memoryService()).isSameInstanceAs(baseMemoryService);

    // constructor(BaseAgent, String, MapDBMemoryService)
    String app4 = "app_const_4";
    MapDBMemoryService mapDbMemory = mock(MapDBMemoryService.class);
    MapDbRunner runner4 = keep(new MapDbRunner(agent, app4, mapDbMemory));
    assertThat(runner4.memoryService()).isSameInstanceAs(mapDbMemory);

    // constructor(BaseAgent, String, List<Plugin>)
    String app5 = "app_const_5";
    Plugin plugin = mockPlugin("plugin");
    MapDbRunner runner5 = keep(new MapDbRunner(agent, app5, ImmutableList.of(plugin)));
    assertThat(runner5.pluginManager().getPlugins()).containsExactly(plugin);

    // constructor(BaseAgent, String, BaseMemoryService, List<Plugin>)
    String app6 = "app_const_6";
    MapDbRunner runner6 =
        keep(new MapDbRunner(agent, app6, baseMemoryService, ImmutableList.of(plugin)));
    assertThat(runner6.memoryService()).isSameInstanceAs(baseMemoryService);
    assertThat(runner6.pluginManager().getPlugins()).containsExactly(plugin);

    // constructor(BaseAgent, String, BaseMemoryService, List<Plugin>, Configs...)
    String app7 = "app_const_7";
    EventsCompactionConfig compactionConfig = new EventsCompactionConfig(5, 1);
    ContextCacheConfig contextCacheConfig = new ContextCacheConfig();
    ResumabilityConfig resumabilityConfig = ResumabilityConfig.builder().build();
    MapDbRunner runner7 =
        keep(
            new MapDbRunner(
                agent,
                app7,
                baseMemoryService,
                ImmutableList.of(plugin),
                compactionConfig,
                contextCacheConfig,
                resumabilityConfig));
    assertThat(runner7.agent()).isSameInstanceAs(agent);

    // constructor(App)
    String app8 = "app_const_8";
    App app = App.builder().name(app8).rootAgent(agent).plugins(ImmutableList.of(plugin)).build();
    MapDbRunner runner8 = keep(new MapDbRunner(app));
    assertThat(runner8.agent()).isSameInstanceAs(agent);
    assertThat(runner8.appName()).isEqualTo(app8);
    assertThat(runner8.pluginManager().getPlugins()).containsExactly(plugin);

    // constructor(App, BaseMemoryService)
    String app9 = "app_const_9";
    App app9Obj =
        App.builder().name(app9).rootAgent(agent).plugins(ImmutableList.of(plugin)).build();
    MapDbRunner runner9 = keep(new MapDbRunner(app9Obj, baseMemoryService));
    assertThat(runner9.memoryService()).isSameInstanceAs(baseMemoryService);
    assertThat(runner9.pluginManager().getPlugins()).containsExactly(plugin);
  }

  @Test
  public void exportToJson_success() throws IOException {
    MapDbRunner runner = keep(MapDbRunner.builder().agent(agent).appName(appName).build());
    Session session = runner.sessionService().createSession(appName, "user1").blockingGet();

    Path exportPath = new File(tempFolder.getRoot(), "export.json").toPath();
    runner.exportToJson(exportPath);

    assertThat(Files.exists(exportPath)).isTrue();
    String jsonContent = Files.readString(exportPath);
    assertThat(jsonContent).isNotEmpty();

    ObjectMapper mapper = new ObjectMapper();
    JsonNode node = mapper.readTree(jsonContent);
    assertThat(node.has("sessions")).isTrue();
    assertThat(node.has("userState")).isTrue();
    assertThat(node.has("appState")).isTrue();
  }

  @Test
  public void exportToJson_throwsWhenSessionServiceNotMapDb() throws IOException {
    MapDbRunner runner =
        keep(
            MapDbRunner.builder()
                .agent(agent)
                .appName("testApp")
                .sessionService(new InMemorySessionService())
                .build());

    Path exportPath = new File(tempFolder.getRoot(), "export_fail.json").toPath();
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> runner.exportToJson(exportPath));
    assertThat(ex)
        .hasMessageThat()
        .contains("Session service is not an instance of MapDbSessionService");
  }

  @Test
  public void runAsync_processesPromptSuccessfully() {
    MapDbRunner runner = keep(MapDbRunner.builder().agent(agent).appName(appName).build());
    Session session = runner.sessionService().createSession(appName, "user1").blockingGet();

    List<Event> events =
        runner.runAsync("user1", session.id(), createContent("Hello")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("testAgent: Hello from MapDbRunner");

    Session updatedSession =
        runner
            .sessionService()
            .getSession(appName, "user1", session.id(), java.util.Optional.empty())
            .blockingGet();
    assertThat(updatedSession).isNotNull();
    assertThat(simplifyEvents(updatedSession.events()))
        .containsExactly("user: Hello", "testAgent: Hello from MapDbRunner");
  }
}
