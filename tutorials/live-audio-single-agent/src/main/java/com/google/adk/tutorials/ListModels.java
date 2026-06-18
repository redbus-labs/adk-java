package com.google.adk.tutorials;

import com.google.genai.Client;
import com.google.genai.types.Model;
import java.util.List;

public class ListModels {
  public static void main(String[] args) throws Exception {
    Client client = Client.builder().build();
    List<Model> models =
        client.models().list(com.google.genai.types.ListModelsConfig.builder().build()).getPage();
    for (Model m : models) {
      if (m.name().get().contains("live")
          || m.name().get().contains("audio")
          || m.name().get().contains("2.0")
          || m.name().get().contains("2.5")
          || m.name().get().contains("3.1")) {
        System.out.println(m.name().get());
      }
    }
  }
}
