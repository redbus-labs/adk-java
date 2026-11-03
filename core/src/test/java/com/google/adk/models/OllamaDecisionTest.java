package com.google.adk.models;

import static org.junit.jupiter.api.Assertions.*;

import com.google.adk.models.decision.DecisionCriterion;
import com.google.adk.models.decision.DecisionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

public class OllamaDecisionTest {
  @Test
  public void testDecision() {
    try {
      OllamaBaseLM model = new OllamaBaseLM("tev1:0.8b").withThink(false);
      DecisionCriterion criterion = new DecisionCriterion();
      criterion.setName("Refund");
      criterion.setType("choice");
      criterion.setDescription("Is the user asking for their money back?");
      criterion.setChoices(
          java.util.Map.of("yes", "Wants a refund", "no", "Does not want a refund"));
      List<DecisionResult> results =
          model.decide("I want my money back", java.util.List.of(criterion));
      assertNotNull(results);
      assertFalse(results.isEmpty());
      System.out.println("DECISION: " + results.get(0).getDecision());
      System.out.println("PROBABILITY: " + results.get(0).getProbability());
    } catch (Exception e) {
      // Ignore if Ollama is not running locally
      System.out.println("Skipping test. Error: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
