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

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public final class InvocationContextTest {

  @Mock private BaseSessionService mockSessionService;
  @Mock private BaseArtifactService mockArtifactService;
  @Mock private BaseAgent mockAgent;
  private Session session;
  private Content userContent;
  private RunConfig runConfig;
  private Map<String, ActiveStreamingTool> activeStreamingTools;
  private LiveRequestQueue liveRequestQueue;
  private String testInvocationId;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    liveRequestQueue = new LiveRequestQueue();
    session = Session.builder("test-session-id").build();
    userContent = Content.builder().build();
    runConfig = RunConfig.builder().build();
    testInvocationId = "test-invocation-id";
    activeStreamingTools = new HashMap<>();
    activeStreamingTools.put("test-tool", new ActiveStreamingTool(new LiveRequestQueue()));
  }

  @Test
  public void testCreateWithUserContent() {
    InvocationContext context =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    assertThat(context).isNotNull();
    assertThat(context.sessionService()).isEqualTo(mockSessionService);
    assertThat(context.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context.liveRequestQueue()).isEmpty();
    assertThat(context.invocationId()).isEqualTo(testInvocationId);
    assertThat(context.agent()).isEqualTo(mockAgent);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.userContent()).hasValue(userContent);
    assertThat(context.runConfig()).isEqualTo(runConfig);
    assertThat(context.endInvocation()).isFalse();
  }

  @Test
  public void testCreateWithNullUserContent() {
    InvocationContext context =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            null, // Pass null for userContent
            runConfig);

    assertThat(context).isNotNull();
    assertThat(context.userContent()).isEmpty();
  }

  @Test
  public void testCreateWithLiveRequestQueue() {
    InvocationContext context =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            mockAgent,
            session,
            liveRequestQueue,
            runConfig);

    assertThat(context).isNotNull();
    assertThat(context.sessionService()).isEqualTo(mockSessionService);
    assertThat(context.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context.liveRequestQueue()).hasValue(liveRequestQueue);
    assertThat(context.invocationId()).startsWith("e-"); // Check format of generated ID
    assertThat(context.agent()).isEqualTo(mockAgent);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.userContent()).isEmpty();
    assertThat(context.runConfig()).isEqualTo(runConfig);
    assertThat(context.endInvocation()).isFalse();
  }

  @Test
  public void testCopyOf() {
    InvocationContext originalContext =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);
    originalContext.activeStreamingTools().putAll(activeStreamingTools);

    InvocationContext copiedContext = InvocationContext.copyOf(originalContext);

    assertThat(copiedContext).isNotNull();
    assertThat(copiedContext).isNotSameInstanceAs(originalContext);

    assertThat(copiedContext.sessionService()).isEqualTo(originalContext.sessionService());
    assertThat(copiedContext.artifactService()).isEqualTo(originalContext.artifactService());
    assertThat(copiedContext.liveRequestQueue()).isEqualTo(originalContext.liveRequestQueue());
    assertThat(copiedContext.invocationId()).isEqualTo(originalContext.invocationId());
    assertThat(copiedContext.agent()).isEqualTo(originalContext.agent());
    assertThat(copiedContext.session()).isEqualTo(originalContext.session());
    assertThat(copiedContext.userContent()).isEqualTo(originalContext.userContent());
    assertThat(copiedContext.runConfig()).isEqualTo(originalContext.runConfig());
    assertThat(copiedContext.endInvocation()).isEqualTo(originalContext.endInvocation());
    assertThat(copiedContext.activeStreamingTools())
        .isEqualTo(originalContext.activeStreamingTools());
  }

  @Test
  public void testGetters() {
    InvocationContext context =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    assertThat(context.sessionService()).isEqualTo(mockSessionService);
    assertThat(context.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context.liveRequestQueue()).isEmpty();
    assertThat(context.invocationId()).isEqualTo(testInvocationId);
    assertThat(context.agent()).isEqualTo(mockAgent);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.userContent()).hasValue(userContent);
    assertThat(context.runConfig()).isEqualTo(runConfig);
    assertThat(context.endInvocation()).isFalse();
  }

  @Test
  public void testSetAgent() {
    InvocationContext context =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    BaseAgent newMockAgent = mock(BaseAgent.class);
    context.agent(newMockAgent);

    assertThat(context.agent()).isEqualTo(newMockAgent);
  }

  @Test
  public void testNewInvocationContextId() {
    String id = InvocationContext.newInvocationContextId();

    assertThat(id).isNotNull();
    assertThat(id).isNotEmpty();
    assertThat(id).startsWith("e-");
    // Basic check for UUID format after "e-"
    assertThat(id.substring(2))
        .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  }

  @Test
  public void testEquals_sameObject() {
    InvocationContext context =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    assertThat(context.equals(context)).isTrue();
  }

  @Test
  public void testEquals_null() {
    InvocationContext context =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    assertThat(context.equals(null)).isFalse();
  }

  @Test
  public void testEquals_sameValues() {
    InvocationContext context1 =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    // Create another context with the same parameters
    InvocationContext context2 =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    assertThat(context1.equals(context2)).isTrue();
    assertThat(context2.equals(context1)).isTrue(); // Check symmetry
  }

  @Test
  public void testEquals_differentValues() {
    InvocationContext context =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    // Create contexts with one field different
    InvocationContext contextWithDiffSessionService =
        InvocationContext.create(
            mock(BaseSessionService.class), // Different mock
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    InvocationContext contextWithDiffInvocationId =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            "another-id", // Different ID
            mockAgent,
            session,
            userContent,
            runConfig);

    InvocationContext contextWithDiffAgent =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mock(BaseAgent.class), // Different mock
            session,
            userContent,
            runConfig);

    InvocationContext contextWithUserContentEmpty =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            null, // User content is null (Optional.empty)
            runConfig);

    InvocationContext contextWithLiveQueuePresent =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            mockAgent,
            session,
            liveRequestQueue, // Live queue is present (Optional.of)
            runConfig);

    assertThat(context.equals(contextWithDiffSessionService)).isFalse();
    assertThat(context.equals(contextWithDiffInvocationId)).isFalse();
    assertThat(context.equals(contextWithDiffAgent)).isFalse();
    assertThat(context.equals(contextWithUserContentEmpty)).isFalse();
    assertThat(context.equals(contextWithLiveQueuePresent)).isFalse();
  }

  @Test
  public void testHashCode_differentValues() {
    InvocationContext context =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    // Create contexts with one field different
    InvocationContext contextWithDiffSessionService =
        InvocationContext.create(
            mock(BaseSessionService.class), // Different mock
            mockArtifactService,
            testInvocationId,
            mockAgent,
            session,
            userContent,
            runConfig);

    InvocationContext contextWithDiffInvocationId =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            "another-id", // Different ID
            mockAgent,
            session,
            userContent,
            runConfig);

    assertThat(context).isNotEqualTo(contextWithDiffSessionService);
    assertThat(context).isNotEqualTo(contextWithDiffInvocationId);
  }
}
