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

package com.google.adk.apps;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Application-wide policy for nudging an inactive user while a live session remains connected. */
public record InactivityNudgeConfig(
    boolean enabled,
    Duration inactivityTimeout,
    String defaultLocale,
    String localeStateKey,
    ImmutableMap<String, String> localizedMessages) {

  private static final Duration DEFAULT_INACTIVITY_TIMEOUT = Duration.ofSeconds(5);
  private static final String DEFAULT_LOCALE = "en";
  private static final String DEFAULT_LOCALE_STATE_KEY = "locale";
  private static final ImmutableMap<String, String> DEFAULT_MESSAGES =
      ImmutableMap.of(DEFAULT_LOCALE, "Hey, are you there?");

  public InactivityNudgeConfig {
    Objects.requireNonNull(inactivityTimeout, "inactivityTimeout cannot be null");
    Objects.requireNonNull(defaultLocale, "defaultLocale cannot be null");
    Objects.requireNonNull(localeStateKey, "localeStateKey cannot be null");
    Objects.requireNonNull(localizedMessages, "localizedMessages cannot be null");
    if (inactivityTimeout.isZero() || inactivityTimeout.isNegative()) {
      throw new IllegalArgumentException("inactivityTimeout must be positive");
    }
    if (defaultLocale.isBlank()) {
      throw new IllegalArgumentException("defaultLocale cannot be blank");
    }
    if (localeStateKey.isBlank()) {
      throw new IllegalArgumentException("localeStateKey cannot be blank");
    }

    ImmutableMap.Builder<String, String> normalizedMessages = ImmutableMap.builder();
    localizedMessages.forEach(
        (locale, message) -> {
          if (locale == null || locale.isBlank()) {
            throw new IllegalArgumentException("Message locale cannot be null or blank");
          }
          if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Nudge message cannot be null or blank");
          }
          normalizedMessages.put(normalizeLocale(locale), message);
        });
    localizedMessages = normalizedMessages.buildOrThrow();

    String normalizedDefaultLocale = normalizeLocale(defaultLocale);
    if (!localizedMessages.containsKey(normalizedDefaultLocale)) {
      throw new IllegalArgumentException(
          "localizedMessages must contain the default locale: " + defaultLocale);
    }
    defaultLocale = normalizedDefaultLocale;
  }

  /** Returns a disabled policy with otherwise usable defaults. */
  public static InactivityNudgeConfig disabled() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Resolves an exact locale, then its language, and finally the configured default locale. */
  public String messageForLocale(String locale) {
    String normalizedLocale =
        locale == null || locale.isBlank() ? defaultLocale : normalizeLocale(locale);
    String exactMessage = localizedMessages.get(normalizedLocale);
    if (exactMessage != null) {
      return exactMessage;
    }

    int regionSeparator = normalizedLocale.indexOf('-');
    if (regionSeparator > 0) {
      String languageMessage =
          localizedMessages.get(normalizedLocale.substring(0, regionSeparator));
      if (languageMessage != null) {
        return languageMessage;
      }
    }
    return localizedMessages.get(defaultLocale);
  }

  private static String normalizeLocale(String locale) {
    return locale.trim().replace('_', '-').toLowerCase(Locale.ROOT);
  }

  /** Builder for {@link InactivityNudgeConfig}. */
  public static final class Builder {
    private boolean enabled;
    private Duration inactivityTimeout = DEFAULT_INACTIVITY_TIMEOUT;
    private String defaultLocale = DEFAULT_LOCALE;
    private String localeStateKey = DEFAULT_LOCALE_STATE_KEY;
    private Map<String, String> localizedMessages = DEFAULT_MESSAGES;

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder inactivityTimeout(Duration inactivityTimeout) {
      this.inactivityTimeout = inactivityTimeout;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder defaultLocale(String defaultLocale) {
      this.defaultLocale = defaultLocale;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder localeStateKey(String localeStateKey) {
      this.localeStateKey = localeStateKey;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder localizedMessages(Map<String, String> localizedMessages) {
      this.localizedMessages = localizedMessages;
      return this;
    }

    public InactivityNudgeConfig build() {
      return new InactivityNudgeConfig(
          enabled,
          inactivityTimeout,
          defaultLocale,
          localeStateKey,
          ImmutableMap.copyOf(localizedMessages));
    }
  }
}
