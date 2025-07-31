/**
 * @author Sandeep Belgavi
 * @since 2025-07-31
 */
package com.google.adk.runner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.CassandraArtifactService;
import com.google.adk.sessions.CassandraSessionService;
import java.io.IOException;

public class CassandraRunner extends Runner {

  public CassandraRunner(BaseAgent agent) throws IOException {
    this(agent, agent.name());
  }

  public CassandraRunner(BaseAgent agent, String appName) throws IOException {
    super(
        agent,
        appName,
        new CassandraArtifactService(appName + "_ART", "" + appName + "_ART"),
        new CassandraSessionService());
  }
}
