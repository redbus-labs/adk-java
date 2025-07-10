package com.google.adk.tools;

import com.google.adk.agents.ReadonlyContext;
import java.util.Optional;

/**
 * Functional interface to decide whether a tool should be exposed to the LLM based on the current
 * context.
 */
@FunctionalInterface
public interface ToolPredicate {
  /**
   * Decides if the given tool is selected.
   *
   * @param tool The tool to check.
   * @param readonlyContext The current context.
   * @return true if the tool should be selected, false otherwise.
   */
  boolean test(BaseTool tool, Optional<ReadonlyContext> readonlyContext);
}
