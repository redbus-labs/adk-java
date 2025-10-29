package com.google.adk.utils;

/*
 * @author Arun Parmar
 * @date 2025-10-28
 * @description PropertiesHelper is a class that loads properties from a file.
 * @version 1.0.0
 * @since 1.0.0
 */

import java.io.File;
import java.io.IOException;
import org.ini4j.Wini;

public class PropertiesHelper {
  private static PropertiesHelper propertiesHelper = null;
  private Wini properties = null;
  private final String filePath;

  private final String env;

  private PropertiesHelper(String filePath, String environment) {
    this.filePath = filePath;
    this.env = environment;
    initPool();
  }

  public static PropertiesHelper loadProperties(String filePath, String environment) {
    if (propertiesHelper == null) {
      propertiesHelper = new PropertiesHelper(filePath, environment);
    }
    return propertiesHelper;
  }

  public static PropertiesHelper getInstance() {
    if (propertiesHelper == null) {
      throw new Error("please load the properties!!");
    }
    return propertiesHelper;
  }

  private void initPool() {
    try {
      properties = new Wini(new File(this.filePath));
    } catch (IOException ex) {
      System.out.println("File Not found, please enter valid path!! ");
    }
  }

  public String getValue(String propKey) {
    return this.properties.get(env, propKey);
  }
}
