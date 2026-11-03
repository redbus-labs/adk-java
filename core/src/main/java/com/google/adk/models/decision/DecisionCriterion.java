package com.google.adk.models.decision;

import java.util.Map;

public class DecisionCriterion {
  private String name;
  private String description;
  private String type;
  private Double weight;
  private Map<String, String> choices;

  public DecisionCriterion() {}

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public Double getWeight() {
    return weight;
  }

  public void setWeight(Double weight) {
    this.weight = weight;
  }

  public Map<String, String> getChoices() {
    return choices;
  }

  public void setChoices(Map<String, String> choices) {
    this.choices = choices;
  }
}
