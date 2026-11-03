package com.google.adk.models.decision;

public class DecisionResult {
  private String criterion;
  private String decision;
  private double probability;
  private Double score;

  public DecisionResult() {}

  public String getCriterion() {
    return criterion;
  }

  public void setCriterion(String criterion) {
    this.criterion = criterion;
  }

  public String getDecision() {
    return decision;
  }

  public void setDecision(String decision) {
    this.decision = decision;
  }

  public double getProbability() {
    return probability;
  }

  public void setProbability(double probability) {
    this.probability = probability;
  }

  public Double getScore() {
    return score;
  }

  public void setScore(Double score) {
    this.score = score;
  }
}
