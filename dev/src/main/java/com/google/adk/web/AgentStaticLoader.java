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
package com.google.adk.web;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Arrays.stream;
import static java.util.function.Function.identity;

import com.google.adk.agents.BaseAgent;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Map;

/**
 * Static Agent Loader.
 *
 * @author <a href="Michael Vorburger.ch">http://www.vorburger.ch/</a>
 */
class AgentStaticLoader implements AgentLoader {

  private final ImmutableMap<String, BaseAgent> agents;

  AgentStaticLoader(BaseAgent... agents) {
    this.agents = stream(agents).collect(toImmutableMap(BaseAgent::name, identity()));
  }

  @Override
  public Map<String, BaseAgent> loadAgents() throws IOException {
    return agents;
  }
}
