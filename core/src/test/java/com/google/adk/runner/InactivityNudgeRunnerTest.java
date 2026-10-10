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

package com.google.adk.runner;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.adk.agents.LiveRequest;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.apps.InactivityNudgeConfig;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.time.Duration;
import org.junit.Test;

public final class InactivityNudgeRunnerTest {

  @Test
  public void openingSilenceInjectsLocalizedNudgeAfterTimeout() {
    Session session = newSession();
    session.state().put("locale", "hi-IN");
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> requests = queue.get().test();
    TestScheduler scheduler = new TestScheduler();
    InactivityNudgeConfig config =
        InactivityNudgeConfig.builder()
            .enabled(true)
            .inactivityTimeout(Duration.ofSeconds(4))
            .defaultLocale("en")
            .localizedMessages(
                ImmutableMap.of("en", "Are you there?", "hi-IN", "क्या आप अभी भी वहाँ हैं?"))
            .build();

    Disposable watcher = Runner.scheduleInactivityNudge(session, queue, config, scheduler);
    scheduler.advanceTimeBy(3, SECONDS);
    requests.assertNoValues();

    scheduler.advanceTimeBy(1, SECONDS);

    requests.assertValueCount(1);
    assertThat(requestText(requests.values().get(0))).contains("क्या आप अभी भी वहाँ हैं?");
    watcher.dispose();
  }

  @Test
  public void textActivityResetsDeadlineAndNudgeCanFireMidSession() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> requests = queue.get().test();
    TestScheduler scheduler = new TestScheduler();
    Runner.scheduleInactivityNudge(newSession(), queue, enabledConfig(), scheduler);

    scheduler.advanceTimeBy(4, SECONDS);
    queue.content(Content.fromParts(Part.fromText("Hello")));
    scheduler.advanceTimeBy(4, SECONDS);
    requests.assertValueCount(1);

    scheduler.advanceTimeBy(1, SECONDS);

    requests.assertValueCount(2);
    assertThat(requestText(requests.values().get(1))).contains("Hey, are you there?");
  }

  @Test
  public void audioActivityResetsDeadline() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> requests = queue.get().test();
    TestScheduler scheduler = new TestScheduler();
    Runner.scheduleInactivityNudge(newSession(), queue, enabledConfig(), scheduler);

    scheduler.advanceTimeBy(4, SECONDS);
    queue.realtime(Blob.builder().mimeType("audio/pcm").data(new byte[] {1, 2, 3}).build());
    scheduler.advanceTimeBy(4, SECONDS);
    requests.assertValueCount(1);

    scheduler.advanceTimeBy(1, SECONDS);
    requests.assertValueCount(2);
  }

  @Test
  public void assistantOutputMovesDeadlineUntilResponseFinishes() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> requests = queue.get().test();
    TestScheduler scheduler = new TestScheduler();
    Runner.scheduleInactivityNudge(newSession(), queue, enabledConfig(), scheduler);

    scheduler.advanceTimeBy(4, SECONDS);
    queue.recordAssistantActivity();
    scheduler.advanceTimeBy(4, SECONDS);
    requests.assertNoValues();

    scheduler.advanceTimeBy(1, SECONDS);
    requests.assertValueCount(1);
  }

  @Test
  public void existingSessionCanReceiveNudge() {
    Session session = newSession();
    session.events().add(Event.builder().id(Event.generateEventId()).author("agent").build());
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> requests = queue.get().test();
    TestScheduler scheduler = new TestScheduler();

    Runner.scheduleInactivityNudge(session, queue, enabledConfig(), scheduler);
    scheduler.advanceTimeBy(5, SECONDS);

    requests.assertValueCount(1);
  }

  @Test
  public void nudgeFiresOncePerIdlePeriodAndRearmsAfterUserActivity() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> requests = queue.get().test();
    TestScheduler scheduler = new TestScheduler();
    Runner.scheduleInactivityNudge(newSession(), queue, enabledConfig(), scheduler);

    scheduler.advanceTimeBy(20, SECONDS);
    requests.assertValueCount(1);

    queue.content(Content.fromParts(Part.fromText("Still here")));
    scheduler.advanceTimeBy(5, SECONDS);

    requests.assertValueCount(3);
    assertThat(requestText(requests.values().get(1))).isEqualTo("Still here");
    assertThat(requestText(requests.values().get(2))).contains("Hey, are you there?");
  }

  @Test
  public void closeCancelsPendingNudge() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> requests = queue.get().test();
    TestScheduler scheduler = new TestScheduler();
    Runner.scheduleInactivityNudge(newSession(), queue, enabledConfig(), scheduler);

    queue.close();
    scheduler.advanceTimeBy(10, SECONDS);

    requests.assertValueCount(1);
    assertThat(requests.values().get(0).shouldClose()).isTrue();
  }

  @Test
  public void disabledPolicyDoesNotScheduleNudge() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> requests = queue.get().test();
    TestScheduler scheduler = new TestScheduler();

    Runner.scheduleInactivityNudge(
        newSession(), queue, InactivityNudgeConfig.disabled(), scheduler);
    scheduler.advanceTimeBy(10, SECONDS);

    requests.assertNoValues();
  }

  @Test
  public void runnerUsesApplicationInactivityPolicy() {
    InactivityNudgeConfig inactivityConfig = enabledConfig();
    App app =
        App.builder()
            .name("app")
            .rootAgent(LlmAgent.builder().name("agent").model("test-model").build())
            .inactivityNudgeConfig(inactivityConfig)
            .build();

    Runner runner = Runner.builder().app(app).build();

    assertThat(runner.inactivityNudgeConfig()).isEqualTo(inactivityConfig);
  }

  private static InactivityNudgeConfig enabledConfig() {
    return InactivityNudgeConfig.builder()
        .enabled(true)
        .inactivityTimeout(Duration.ofSeconds(5))
        .build();
  }

  private static Session newSession() {
    return Session.builder("session").appName("app").userId("user").build();
  }

  private static String requestText(LiveRequest request) {
    return request.content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
  }
}
