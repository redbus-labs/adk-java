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

package com.google.adk.web;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ConfigAgentUtils;
import com.google.adk.web.config.AgentLoadingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hot-loading service for YAML-based agents. Monitors the configured source directory for
 * `root_agent.yaml` files and automatically reloads agents when the files change.
 *
 * <p>This service complements the {@link AgentCompilerLoader} by providing hot-reloading
 * capabilities for YAML-configured agents without requiring compilation.
 *
 * <p>Hot-reloading can be disabled for production environments by setting
 * adk.agent.hotReloadingEnabled=false
 *
 * <p>TODO: Config agent features are not yet ready for public use.
 */
@Service
public class AgentYamlHotLoader implements AgentLoader {
  private static final Logger logger = LoggerFactory.getLogger(AgentYamlHotLoader.class);
  private static final String YAML_CONFIG_FILENAME = "root_agent.yaml";

  private final boolean hotReloadingEnabled;
  private final AgentLoadingProperties properties;
  private final Map<String, BaseAgent> agentRegistry;
  private final AdkWebServer.RunnerService runnerService;
  private final Map<Path, Long> watchedFiles = new ConcurrentHashMap<>();
  private final Map<Path, String> pathToAgentName = new ConcurrentHashMap<>();
  private final ScheduledExecutorService fileWatcher = Executors.newSingleThreadScheduledExecutor();
  private volatile boolean started = false;

  /**
   * Creates a new AgentYamlHotLoader.
   *
   * @param properties Configuration properties for agent loading
   * @param agentRegistry The shared agent registry to update when agents are loaded/reloaded
   * @param runnerService The runner service for cache invalidation
   * @param hotReloadingEnabled Controls whether hot-reloading is enabled
   */
  public AgentYamlHotLoader(
      AgentLoadingProperties properties,
      Map<String, BaseAgent> agentRegistry,
      AdkWebServer.RunnerService runnerService,
      @Value("${adk.agent.hotReloadingEnabled:true}") boolean hotReloadingEnabled) {
    this.properties = properties;
    this.agentRegistry = agentRegistry;
    this.runnerService = runnerService;
    this.hotReloadingEnabled = hotReloadingEnabled;
  }

  @Override
  public String getLoaderType() {
    return hotReloadingEnabled ? "YAML Agents (Hot-Reloading)" : "YAML Agents (Static)";
  }

  @Override
  public boolean supportsHotReloading() {
    return hotReloadingEnabled;
  }

  @Override
  public Map<String, BaseAgent> loadAgents() throws IOException {
    if (properties.getSourceDir() == null || properties.getSourceDir().isEmpty()) {
      logger.info("Agent source directory not configured. YAML loader will not load any agents.");
      return new HashMap<>();
    }

    Path sourceDir = Paths.get(properties.getSourceDir());
    if (!Files.isDirectory(sourceDir)) {
      logger.warn(
          "Agent source directory does not exist: {}. YAML loader will not load any agents.",
          sourceDir);
      return new HashMap<>();
    }

    logger.info("Initial scan for YAML agents in: {}", sourceDir);
    Map<String, BaseAgent> loadedAgents = new HashMap<>();

    try (Stream<Path> entries = Files.list(sourceDir)) {
      for (Path agentDir : entries.collect(java.util.stream.Collectors.toList())) {
        if (Files.isDirectory(agentDir)) {
          Path yamlConfigPath = agentDir.resolve(YAML_CONFIG_FILENAME);
          if (Files.exists(yamlConfigPath) && Files.isRegularFile(yamlConfigPath)) {
            try {
              logger.info("Loading YAML agent from: {}", yamlConfigPath);
              BaseAgent agent = ConfigAgentUtils.fromConfig(yamlConfigPath.toString());
              if (loadedAgents.containsKey(agent.name())) {
                logger.warn(
                    "Duplicate agent name '{}' found in {}. Overwriting.",
                    agent.name(),
                    yamlConfigPath);
              }
              loadedAgents.put(agent.name(), agent);
              logger.info(
                  "Successfully loaded YAML agent '{}' from: {}", agent.name(), yamlConfigPath);
            } catch (Exception e) {
              logger.error("Failed to load YAML agent from: {}", yamlConfigPath, e);
            }
          }
        }
      }
    }

    logger.info("Initial YAML agent scan complete. Loaded {} agents.", loadedAgents.size());
    return loadedAgents;
  }

  /**
   * Starts the hot-loading service. Sets up file watching. Initial loading should be done via
   * {@link #loadAgents()}.
   *
   * @throws IOException if there's an error accessing the source directory
   */
  public synchronized void start() throws IOException {
    if (!hotReloadingEnabled) {
      logger.info(
          "Hot-reloading is disabled (adk.agent.hotReloadingEnabled=false). YAML agents will be"
              + " loaded once at startup and will not be monitored for changes.");
      return;
    }

    if (started) {
      logger.warn("AgentYamlHotLoader is already started");
      return;
    }

    if (properties.getSourceDir() == null || properties.getSourceDir().isEmpty()) {
      logger.info("Agent source directory not configured. YAML hot-loader will not start.");
      return;
    }

    Path sourceDir = Paths.get(properties.getSourceDir());
    if (!Files.isDirectory(sourceDir)) {
      logger.warn(
          "Agent source directory does not exist: {}. YAML hot-loader will not start.", sourceDir);
      return;
    }

    logger.info("Starting AgentYamlHotLoader file watcher for directory: {}", sourceDir);

    watchedFiles.clear();
    pathToAgentName.clear();

    // Look for agent directories (immediate subdirectories only)
    try (Stream<Path> entries = Files.list(sourceDir)) {
      entries
          .filter(Files::isDirectory)
          .forEach(
              agentDir -> {
                Path yamlConfigPath = agentDir.resolve(YAML_CONFIG_FILENAME);
                if (Files.exists(yamlConfigPath) && Files.isRegularFile(yamlConfigPath)) {
                  try {
                    BaseAgent agent = ConfigAgentUtils.fromConfig(yamlConfigPath.toString());
                    watchedFiles.put(yamlConfigPath, getLastModified(yamlConfigPath));
                    pathToAgentName.put(yamlConfigPath, agent.name());
                    logger.debug("Watching file: {} for agent: {}", yamlConfigPath, agent.name());
                  } catch (Exception e) {
                    logger.error(
                        "Failed to read agent name from YAML for watcher setup: {}",
                        yamlConfigPath,
                        e);
                  }
                }
              });
    }

    fileWatcher.scheduleAtFixedRate(this::checkForChanges, 2, 2, TimeUnit.SECONDS);
    started = true;

    Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

    logger.info(
        "AgentYamlHotLoader file watcher started successfully. Watching {} YAML files.",
        watchedFiles.size());
  }

  /** Stops the hot-loading service. */
  public synchronized void stop() {
    if (!started) {
      return;
    }

    logger.info("Stopping AgentYamlHotLoader...");
    fileWatcher.shutdown();
    try {
      if (!fileWatcher.awaitTermination(5, TimeUnit.SECONDS)) {
        fileWatcher.shutdownNow();
      }
    } catch (InterruptedException e) {
      fileWatcher.shutdownNow();
      Thread.currentThread().interrupt();
    }
    started = false;
    logger.info("AgentYamlHotLoader stopped.");
  }

  /**
   * Returns the current state of watched files for debugging purposes.
   *
   * @return A map of watched file paths to their last modification times
   */
  public Map<Path, Long> getWatchedFiles() {
    return new HashMap<>(watchedFiles);
  }

  /**
   * Returns whether hot-reloading is currently enabled.
   *
   * @return true if hot-reloading is enabled, false otherwise
   */
  public boolean isHotReloadingEnabled() {
    return hotReloadingEnabled;
  }

  /**
   * Loads or reloads a YAML agent from the specified path into the agentRegistry.
   *
   * @param yamlConfigPath The path to the YAML configuration file
   * @throws Exception if loading fails
   */
  private void loadOrReloadYamlAgentIntoRegistry(Path yamlConfigPath) throws Exception {
    logger.debug("Loading/Reloading YAML agent from: {}", yamlConfigPath);

    BaseAgent agent = ConfigAgentUtils.fromConfig(yamlConfigPath.toString());

    String oldAgentName = pathToAgentName.get(yamlConfigPath);
    if (oldAgentName != null && !oldAgentName.equals(agent.name())) {
      agentRegistry.remove(oldAgentName);
      runnerService.onAgentUpdated(oldAgentName);
      logger.info(
          "Removed old agent '{}' from registry due to name change in {}",
          oldAgentName,
          yamlConfigPath);
    }

    agentRegistry.put(agent.name(), agent);
    pathToAgentName.put(yamlConfigPath, agent.name());
    watchedFiles.put(yamlConfigPath, getLastModified(yamlConfigPath));

    if (runnerService != null) {
      runnerService.onAgentUpdated(agent.name());
      logger.info("Invalidated Runner cache for reloaded agent: {}", agent.name());
    }

    logger.info(
        "Successfully loaded/reloaded YAML agent '{}' into registry from: {}",
        agent.name(),
        yamlConfigPath);
  }

  /** Checks all watched files for changes and reloads them if necessary. */
  private void checkForChanges() {
    if (!hotReloadingEnabled) {
      return;
    }

    scanForNewYamlFiles();

    for (Map.Entry<Path, Long> entry : new HashMap<>(watchedFiles).entrySet()) {
      Path configPath = entry.getKey();
      Long lastKnownModified = entry.getValue();

      try {
        if (!Files.exists(configPath)) {
          handleFileDeleted(configPath);
          continue;
        }

        long currentModified = getLastModified(configPath);
        if (currentModified > lastKnownModified) {
          logger.info("Detected change in YAML config: {}", configPath);
          try {
            loadOrReloadYamlAgentIntoRegistry(configPath);
          } catch (Exception e) {
            logger.error("Failed to reload YAML agent from: {}", configPath, e);
          }
        }
      } catch (Exception e) {
        logger.error("Error checking file for changes: {}", configPath, e);
      }
    }
  }

  /** Scans the source directory for any new root_agent.yaml files that are not being watched. */
  private void scanForNewYamlFiles() {
    Path sourceDir = Paths.get(properties.getSourceDir());
    if (!Files.isDirectory(sourceDir)) {
      return;
    }

    try (Stream<Path> entries = Files.list(sourceDir)) {
      entries
          .filter(Files::isDirectory)
          .forEach(
              agentDir -> {
                Path yamlConfigPath = agentDir.resolve(YAML_CONFIG_FILENAME);
                if (Files.exists(yamlConfigPath)
                    && Files.isRegularFile(yamlConfigPath)
                    && !watchedFiles.containsKey(yamlConfigPath)) {
                  logger.info("Detected new YAML config file: {}", yamlConfigPath);
                  try {
                    loadOrReloadYamlAgentIntoRegistry(yamlConfigPath);
                  } catch (Exception e) {
                    logger.error("Failed to load new YAML agent from: {}", yamlConfigPath, e);
                  }
                }
              });
    } catch (IOException e) {
      logger.error("Error scanning for new YAML files in: {}", sourceDir, e);
    }
  }

  /**
   * Handles the deletion of a watched YAML file.
   *
   * @param deletedPath The path of the deleted file
   */
  private void handleFileDeleted(Path deletedPath) {
    logger.info("YAML config file deleted: {}", deletedPath);

    watchedFiles.remove(deletedPath);
    String agentName = pathToAgentName.remove(deletedPath);

    if (agentName != null) {
      agentRegistry.remove(agentName);
      logger.info("Removed agent '{}' from registry due to deleted config file", agentName);
      runnerService.onAgentUpdated(agentName);
    }
  }

  /**
   * Gets the last modified time of a file, handling potential I/O errors.
   *
   * @param path The file path
   * @return The last modified time in milliseconds, or 0 if there's an error
   */
  private long getLastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      logger.warn("Could not get last modified time for: {}", path, e);
      return 0;
    }
  }
}
