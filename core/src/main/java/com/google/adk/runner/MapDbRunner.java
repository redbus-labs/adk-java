/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.runner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.MapDbArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.MapDbSessionService;
import java.io.IOException;

/** The class for the in-memory GenAi runner, using in-memory artifact and session services. */
public class MapDbRunner extends Runner {

  public MapDbRunner(BaseAgent agent) throws IOException {
    // TODO: Change the default appName to InMemoryRunner to align with adk python.
    // Check the dev UI in case we break something there.
    this(agent, /* appName= */ agent.name());
  }

  public MapDbRunner(BaseAgent agent, String appName) throws IOException {
    super(
        agent,
        appName,
        new MapDbArtifactService(appName + "_ART"),
        new MapDbSessionService(appName),
        new InMemoryMemoryService());
  }
}
