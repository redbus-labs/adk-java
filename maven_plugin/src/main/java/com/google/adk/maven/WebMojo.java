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

package com.google.adk.maven;

import com.google.adk.maven.web.AdkWebServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Maven plugin goal that starts the Google ADK Web Server with user-provided agents.
 *
 * <p>This Mojo provides a convenient way for developers to test and interact with their agents
 * through ADK Web UI. The plugin dynamically loads user-defined agents and makes them available
 * through a browser interface.
 *
 * <h3>Basic Usage</h3>
 *
 * <pre>{@code
 * mvn google-adk:web -Dagents=com.example.MyAgentProvider
 * }</pre>
 *
 * <h3>Configuration Parameters</h3>
 *
 * <ul>
 *   <li><strong>agents</strong> (required) - Full class path to AgentProvider implementation
 *   <li><strong>port</strong> (optional, default: 8000) - Server port
 *   <li><strong>host</strong> (optional, default: localhost) - Server host address
 *   <li><strong>hotReloading</strong> (optional, default: true) - Enable hot reloading for
 *       config-based agents
 * </ul>
 *
 * <h3>AgentProvider Implementation</h3>
 *
 * <p>The agents parameter should point to a class that implements {@link AgentProvider}. It can
 * reference either:
 *
 * <ul>
 *   <li>A static field: {@code com.example.MyProvider.INSTANCE}
 *   <li>A class with default constructor: {@code com.example.MyProvider}
 * </ul>
 *
 * <h3>Web Interface</h3>
 *
 * <p>Once started, ADK Web UI is available at {@code http://host:port} where users can interact
 * with available agents.
 *
 * @author Google ADK Team
 * @since 0.2.1
 */
@Mojo(name = "web", requiresDependencyResolution = ResolutionScope.RUNTIME)
@Execute(phase = LifecyclePhase.COMPILE)
public class WebMojo extends AbstractMojo {

  /** The Maven project instance. */
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  /**
   * Full class path to the AgentProvider instance.
   *
   * <p>This parameter specifies the AgentProvider implementation that will supply the agents for
   * the web server. It can be specified in two formats:
   *
   * <ul>
   *   <li><strong>Static field reference:</strong> {@code com.example.MyProvider.INSTANCE}
   *   <li><strong>Class name:</strong> {@code com.example.MyProvider} (requires default
   *       constructor)
   * </ul>
   *
   * <p>Example:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=com.example.MyAgentProvider
   * }</pre>
   */
  @Parameter(property = "agents")
  private String agents;

  /**
   * Port number for the web server.
   *
   * <p>The web server will listen on this port. Must be between 1 and 65535. Default is 8000.
   *
   * <p>Example:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=... -Dport=9090
   * }</pre>
   */
  @Parameter(property = "port", defaultValue = "8000")
  private int port;

  /**
   * Host address to bind the web server to.
   *
   * <p>The web server will bind to this host address. Use "localhost" or "127.0.0.1" for local
   * access only, or "0.0.0.0" to accept connections from any network interface. Default is
   * localhost.
   *
   * <p>Example:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=... -Dhost=0.0.0.0
   * }</pre>
   */
  @Parameter(property = "host", defaultValue = "localhost")
  private String host;

  /**
   * Whether to enable hot reloading of agent configurations.
   *
   * <p>When enabled, certain agent configurations may be reloaded without restarting the server.
   * Default is true.
   */
  @Parameter(property = "hotReloading", defaultValue = "true")
  private boolean hotReloading;

  private ConfigurableApplicationContext applicationContext;
  private URLClassLoader projectClassLoader;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    getLog().info("Starting Google ADK Web Server...");
    getLog().info("Plugin version: " + getClass().getPackage().getImplementationVersion());

    validateParameters();
    logConfiguration();

    try {
      // Create custom classloader with project dependencies
      projectClassLoader = createProjectClassLoader();
      Thread.currentThread().setContextClassLoader(projectClassLoader);

      // Load and instantiate the AgentProvider
      getLog().info("Loading agent provider: " + agents);
      AgentProvider provider = loadAgentProvider();

      // Set up system properties for Spring Boot
      setupSystemProperties();

      // Start the Spring Boot application with custom agent provider
      SpringApplication app = new SpringApplication(AdkWebServer.class);

      // Add the agent provider as a bean
      app.addInitializers(
          ctx -> {
            ctx.getBeanFactory().registerSingleton("agentProvider", provider);
          });

      getLog().info("Starting Spring Boot application...");
      applicationContext = app.run(new String[0]);

      getLog().info("🎉 ADK Web Server started successfully!");
      getLog().info("🌐 Web UI available at: http://" + host + ":" + port);
      getLog().info("⏹️  Press Ctrl+C to stop the server...");
      getLog().info("");

      // Keep the server running until interrupted
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    getLog().info("Shutting down ADK Web Server...");
                    cleanupResources();
                  }));

      // Wait for shutdown signal
      Thread.currentThread().join();

    } catch (InterruptedException e) {
      getLog().info("Server interrupted, shutting down...");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      cleanupResources();
      throw new MojoExecutionException("Failed to start ADK Web Server", e);
    }
  }

  /** Validates all plugin parameters for correctness and completeness. */
  private void validateParameters() throws MojoFailureException {
    if (agents == null || agents.trim().isEmpty()) {
      throw new MojoFailureException(
          "agents parameter is required. "
              + "Usage: mvn google-adk:web -Dagents=com.example.MyProvider.INSTANCE");
    }

    if (port < 1 || port > 65535) {
      throw new MojoFailureException("Port must be between 1 and 65535, got: " + port);
    }
  }

  /** Logs the current configuration for debugging purposes. */
  private void logConfiguration() {
    getLog().info("Configuration:");
    getLog().info("  Agent Provider: " + agents);
    getLog().info("  Server Host: " + host);
    getLog().info("  Server Port: " + port);
    getLog().info("  Hot Reloading: " + hotReloading);
  }

  private void setupSystemProperties() {
    System.setProperty("server.address", host);
    System.setProperty("server.port", String.valueOf(port));
    System.setProperty("adk.agent.hotReloadingEnabled", String.valueOf(hotReloading));
  }

  /**
   * Creates a classloader that can access the user's project classes and dependencies.
   *
   * <p>This is necessary because the Maven plugin runs in its own classloader context, but needs to
   * load the user's AgentProvider class and its dependencies at runtime.
   *
   * @return URLClassLoader containing the user's compiled classes and runtime dependencies
   * @throws MojoExecutionException if project dependencies cannot be resolved
   */
  private URLClassLoader createProjectClassLoader() throws MojoExecutionException {
    try {
      List<String> classpathElements = new ArrayList<>();
      classpathElements.addAll(project.getCompileClasspathElements());
      classpathElements.addAll(project.getRuntimeClasspathElements());

      List<URL> classpathUrls = new ArrayList<>();
      for (String element : classpathElements) {
        try {
          classpathUrls.add(new URL("file:" + element + (element.endsWith(".jar") ? "" : "/")));
        } catch (MalformedURLException e) {
          getLog().warn("Skipping invalid classpath element: " + element);
        }
      }

      return new URLClassLoader(
          classpathUrls.toArray(URL[]::new), Thread.currentThread().getContextClassLoader());
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Failed to resolve project dependencies", e);
    }
  }

  private AgentProvider loadAgentProvider() throws MojoExecutionException {
    // First, try to interpret as class.field syntax
    if (agents.contains(".")) {
      AgentProvider provider = tryLoadFromStaticField();
      if (provider != null) {
        return provider;
      }
    }

    // Fallback to trying the entire string as a class name
    return tryLoadFromConstructor();
  }

  /**
   * Attempts to load an AgentProvider from a static field reference.
   *
   * @return AgentProvider instance if successful, null if the field approach should be abandoned
   * @throws MojoExecutionException if field is found but has issues (wrong type, access problems)
   */
  private AgentProvider tryLoadFromStaticField() throws MojoExecutionException {
    int lastDotIndex = agents.lastIndexOf('.');
    String className = agents.substring(0, lastDotIndex);
    String fieldName = agents.substring(lastDotIndex + 1);

    try {
      Class<?> providerClass = projectClassLoader.loadClass(className);
      Field field = providerClass.getField(fieldName);

      Object instance = field.get(null);
      if (!(instance instanceof AgentProvider)) {
        throw new MojoExecutionException(
            "Field "
                + fieldName
                + " in class "
                + className
                + " is not an instance of AgentProvider");
      }
      return (AgentProvider) instance;

    } catch (ClassNotFoundException | NoSuchFieldException e) {
      // Field approach failed, return null to try constructor approach
      return null;
    } catch (IllegalAccessException e) {
      throw new MojoExecutionException(
          "Cannot access field " + fieldName + " in class " + className, e);
    }
  }

  /**
   * Attempts to load an AgentProvider by instantiating the class using its default constructor.
   *
   * @return AgentProvider instance
   * @throws MojoExecutionException if class cannot be found or instantiated
   */
  private AgentProvider tryLoadFromConstructor() throws MojoExecutionException {
    try {
      Class<?> providerClass = projectClassLoader.loadClass(agents);
      Object instance = providerClass.getDeclaredConstructor().newInstance();
      if (!(instance instanceof AgentProvider)) {
        throw new MojoExecutionException("Class " + agents + " does not implement AgentProvider");
      }
      return (AgentProvider) instance;

    } catch (ClassNotFoundException e) {
      throw new MojoExecutionException(
          "AgentProvider class not found: "
              + agents
              + ". Make sure the class is in your project's classpath.",
          e);
    } catch (Exception e) {
      throw new MojoExecutionException(
          "Failed to instantiate "
              + agents
              + ". Make sure it has a public default constructor or use the field syntax: "
              + "com.example.MyProvider.INSTANCE",
          e);
    }
  }

  /** Cleans up all resources including application context, classloader. */
  private void cleanupResources() {
    getLog().debug("Cleaning up resources...");

    // Close application context
    if (applicationContext != null) {
      try {
        applicationContext.close();
        getLog().debug("Application context closed");
      } catch (Exception e) {
        getLog().warn("Error closing application context", e);
      }
    }

    // Close project classloader
    if (projectClassLoader != null) {
      try {
        projectClassLoader.close();
        getLog().debug("Project classloader closed");
      } catch (IOException e) {
        getLog().warn("Error closing project classloader", e);
      }
    }

    // Clear system properties to avoid memory leaks
    clearSystemProperties();

    getLog().info("Resource cleanup completed");
  }

  /** Clears system properties set by this plugin to prevent memory leaks. */
  private void clearSystemProperties() {
    try {
      System.clearProperty("server.address");
      System.clearProperty("server.port");
      System.clearProperty("adk.agent.hotReloadingEnabled");
      System.clearProperty("adk.agents.source-dir");
      System.clearProperty("spring.autoconfigure.exclude");
      getLog().debug("System properties cleared");
    } catch (Exception e) {
      getLog().warn("Error clearing system properties", e);
    }
  }
}
