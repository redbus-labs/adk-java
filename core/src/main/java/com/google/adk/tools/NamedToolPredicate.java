package com.google.adk.tools;

import com.google.adk.agents.ReadonlyContext;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;

public class NamedToolPredicate implements ToolPredicate {

  private final ImmutableList<String> toolNames;

  public NamedToolPredicate(List<String> toolNames) {
    this.toolNames = ImmutableList.copyOf(toolNames);
  }

  public NamedToolPredicate(String... toolNames) {
    this.toolNames = ImmutableList.copyOf(toolNames);
  }

  @Override
  public boolean test(BaseTool tool, Optional<ReadonlyContext> readonlyContext) {
    return toolNames.contains(tool.name());
  }
}
