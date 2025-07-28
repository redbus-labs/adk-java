package com.google.adk.runner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.PostegresArtifactService;
import com.google.adk.sessions.PostgresSessionService;
import java.io.IOException;
import java.sql.SQLException;

/**
 * @author Arun Parmar
 */
public class PostgresRunner extends Runner {
  public PostgresRunner(BaseAgent agent) throws IOException, SQLException {

    this(agent, /* appName= */ agent.name());
  }

  public PostgresRunner(BaseAgent agent, String appName) throws IOException, SQLException {
    super(
        agent,
        appName,
        new PostegresArtifactService(appName + "_ART", "" + appName + "_ART"),
        new PostgresSessionService());
  }
}
