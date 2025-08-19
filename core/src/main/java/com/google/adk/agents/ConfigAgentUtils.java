/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.agents;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.adk.utils.ComponentRegistry;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for loading agent configurations from YAML files.
 *
 * <p>TODO: Config agent features are not yet ready for public use.
 */
public final class ConfigAgentUtils {

  private static final Logger logger = LoggerFactory.getLogger(ConfigAgentUtils.class);

  private ConfigAgentUtils() {}

  /**
   * Load agent from a YAML config file path.
   *
   * @param configPath the path to a YAML config file
   * @return the created agent instance as a {@link BaseAgent}
   * @throws ConfigurationException if loading fails
   */
  public static BaseAgent fromConfig(String configPath) throws ConfigurationException {

    File configFile = new File(configPath);
    if (!configFile.exists()) {
      logger.error("Config file not found: {}", configPath);
      throw new ConfigurationException("Config file not found: " + configPath);
    }

    String absolutePath = configFile.getAbsolutePath();

    try {
      // Load the base config to determine the agent class
      BaseAgentConfig baseConfig = loadConfigAsType(absolutePath, BaseAgentConfig.class);
      Class<? extends BaseAgent> agentClass =
          ComponentRegistry.resolveAgentClass(baseConfig.agentClass());

      // Load the config file with the specific config class
      Class<? extends BaseAgentConfig> configClass = getConfigClassForAgent(agentClass);
      BaseAgentConfig config = loadConfigAsType(absolutePath, configClass);
      logger.info("agentClass value = '{}'", config.agentClass());

      // Use reflection to call the fromConfig method with the correct types
      java.lang.reflect.Method fromConfigMethod =
          agentClass.getDeclaredMethod("fromConfig", configClass, String.class);
      return (BaseAgent) fromConfigMethod.invoke(null, config, absolutePath);

    } catch (ConfigurationException e) {
      throw e;
    } catch (Exception e) {
      throw new ConfigurationException("Failed to create agent from config: " + configPath, e);
    }
  }

  /**
   * Load configuration from a YAML file path as a specific type.
   *
   * @param configPath the absolute path to the config file
   * @param configClass the class to deserialize the config into
   * @return the loaded configuration
   * @throws ConfigurationException if loading fails
   */
  private static <T extends BaseAgentConfig> T loadConfigAsType(
      String configPath, Class<T> configClass) throws ConfigurationException {
    try (InputStream inputStream = new FileInputStream(configPath)) {
      ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      return mapper.readValue(inputStream, configClass);
    } catch (IOException e) {
      throw new ConfigurationException("Failed to load or parse config file: " + configPath, e);
    }
  }

  /**
   * Maps agent classes to their corresponding config classes.
   *
   * @param agentClass the agent class
   * @return the corresponding config class
   */
  private static Class<? extends BaseAgentConfig> getConfigClassForAgent(
      Class<? extends BaseAgent> agentClass) {

    if (agentClass == LlmAgent.class) {
      return LlmAgentConfig.class;
    }

    // TODO: Add more agent class to config class mappings as needed
    // Example:
    // if (agentClass == CustomAgent.class) {
    //   return CustomAgentConfig.class;
    // }

    // Default fallback to BaseAgentConfig
    return BaseAgentConfig.class;
  }

  /** Exception thrown when configuration is invalid. */
  public static class ConfigurationException extends Exception {
    public ConfigurationException(String message) {
      super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
