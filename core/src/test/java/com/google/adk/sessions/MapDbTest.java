package com.google.adk.sessions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.Test;

public class MapDbTest {
  @Test
  public void test() throws Exception {
    Session session =
        Session.builder(UUID.randomUUID().toString())
            .appName("app")
            .userId("user")
            .lastUpdateTime(Instant.now())
            .build();
    String json = session.toJson();
    try {
      Session decoded = new ObjectMapper().readValue(json, Session.class);
      System.out.println("SUCCESS!");
    } catch (Exception e) {
      System.out.println("EXCEPTION CAUGHT: " + e.getMessage());
      e.printStackTrace();
      throw e;
    }
  }
}
