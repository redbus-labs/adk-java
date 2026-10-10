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

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.MulticastProcessor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** A queue of live requests to be sent to the model. */
public final class LiveRequestQueue {
  private final FlowableProcessor<LiveRequest> processor;
  private final Object activityLock = new Object();
  private final List<InactivityWatcher> inactivityWatchers = new ArrayList<>();
  private boolean closed;

  public LiveRequestQueue() {
    MulticastProcessor<LiveRequest> processor = MulticastProcessor.<LiveRequest>create();
    processor.start();
    this.processor = processor.toSerialized();
  }

  public void close() {
    send(LiveRequest.builder().close(true).build());
  }

  public void content(Content content) {
    send(LiveRequest.builder().content(content).build());
  }

  public void realtime(Blob blob) {
    send(LiveRequest.builder().blob(blob).build());
  }

  public void send(LiveRequest request) {
    Objects.requireNonNull(request, "request cannot be null");
    synchronized (activityLock) {
      if (closed) {
        return;
      }
      if (request.shouldClose()) {
        closed = true;
        inactivityWatchers.forEach(InactivityWatcher::disposeLocked);
        inactivityWatchers.clear();
      } else {
        inactivityWatchers.forEach(InactivityWatcher::recordUserActivityLocked);
      }
      sendInternal(request);
    }
  }

  /**
   * Records an assistant response as session activity.
   *
   * <p>Assistant activity moves an armed inactivity deadline so users get the full configured time
   * to respond after the assistant finishes. It does not rearm a watcher that has already emitted a
   * nudge; only new user activity does that.
   */
  public void recordAssistantActivity() {
    synchronized (activityLock) {
      if (!closed) {
        inactivityWatchers.forEach(InactivityWatcher::recordAssistantActivityLocked);
      }
    }
  }

  /**
   * Sends backend content after each period of inactivity while this live queue remains open.
   *
   * <p>The first deadline starts immediately. User text/audio/video resets and rearms it. Assistant
   * output moves an armed deadline. After the content is sent, the watcher remains disarmed until
   * the next user request, preventing repeated nudges during one idle period.
   */
  public Disposable watchForInactivity(
      Duration timeout, Scheduler scheduler, Supplier<Content> contentSupplier) {
    Objects.requireNonNull(timeout, "timeout cannot be null");
    Objects.requireNonNull(scheduler, "scheduler cannot be null");
    Objects.requireNonNull(contentSupplier, "contentSupplier cannot be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }

    InactivityWatcher watcher = new InactivityWatcher(timeout, scheduler, contentSupplier);
    synchronized (activityLock) {
      if (closed) {
        return Disposable.disposed();
      }
      inactivityWatchers.add(watcher);
      watcher.recordUserActivityLocked();
    }
    return Disposable.fromRunnable(() -> removeWatcher(watcher));
  }

  private void removeWatcher(InactivityWatcher watcher) {
    synchronized (activityLock) {
      if (inactivityWatchers.remove(watcher)) {
        watcher.disposeLocked();
      }
    }
  }

  private void sendInternal(LiveRequest request) {
    processor.onNext(request);
    if (request.shouldClose()) {
      processor.onComplete();
    }
  }

  public Flowable<LiveRequest> get() {
    return processor;
  }

  private final class InactivityWatcher {
    private final Duration timeout;
    private final Scheduler scheduler;
    private final Supplier<Content> contentSupplier;
    private Disposable scheduledTask = Disposable.disposed();
    private long generation;
    private boolean armed;
    private boolean disposed;

    private InactivityWatcher(
        Duration timeout, Scheduler scheduler, Supplier<Content> contentSupplier) {
      this.timeout = timeout;
      this.scheduler = scheduler;
      this.contentSupplier = contentSupplier;
    }

    private void recordUserActivityLocked() {
      armed = true;
      scheduleLocked();
    }

    private void recordAssistantActivityLocked() {
      if (armed) {
        scheduleLocked();
      }
    }

    private void scheduleLocked() {
      if (disposed) {
        return;
      }
      scheduledTask.dispose();
      long scheduledGeneration = ++generation;
      scheduledTask =
          scheduler.scheduleDirect(
              () -> emitIfStillInactive(scheduledGeneration),
              timeout.toNanos(),
              TimeUnit.NANOSECONDS);
    }

    private void emitIfStillInactive(long scheduledGeneration) {
      synchronized (activityLock) {
        if (closed || disposed || !armed || generation != scheduledGeneration) {
          return;
        }
        armed = false;
        sendInternal(LiveRequest.builder().content(contentSupplier.get()).build());
      }
    }

    private void disposeLocked() {
      disposed = true;
      armed = false;
      generation++;
      scheduledTask.dispose();
    }
  }
}
