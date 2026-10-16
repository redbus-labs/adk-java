package com.google.adk.models.decision;

import java.util.List;

public interface IDecisionModel {
  List<DecisionResult> decide(String state, List<DecisionCriterion> criteria);
}
