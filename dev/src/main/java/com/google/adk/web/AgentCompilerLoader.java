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
import com.google.adk.web.config.AgentLoadingProperties;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dynamically compiles and loads ADK {@link BaseAgent} implementations from source files. It
 * orchestrates the discovery of the ADK core JAR, compilation of agent sources using the Eclipse
 * JDT (ECJ) compiler, and loading of compiled agents into isolated classloaders. Agents are
 * identified by a public static field named {@code ROOT_AGENT}. Supports agent organization in
 * subdirectories or as individual {@code .java} files.
 */
@Service
public class AgentCompilerLoader implements AgentLoader {
  private static final Logger logger = LoggerFactory.getLogger(AgentCompilerLoader.class);
  private final AgentLoadingProperties properties;
  private Path compiledAgentsOutputDir;
  private final String adkCoreJarPathForCompilation;

  @Override
  public String getLoaderType() {
    return "Compiled Java Agents";
  }

  @Override
  public boolean supportsHotReloading() {
    return false;
  }

  /**
   * Initializes the loader with agent configuration and proactively attempts to locate the ADK core
   * JAR. This JAR, containing {@link BaseAgent} and other core ADK types, is crucial for agent
   * compilation. The location strategy (see {@link #locateAndPrepareAdkCoreJar()}) includes
   * handling directly available JARs and extracting nested JARs (e.g., in Spring Boot fat JARs) to
   * ensure it's available for the compilation classpath.
   *
   * @param properties Configuration detailing agent source locations and compilation settings.
   */
  public AgentCompilerLoader(AgentLoadingProperties properties) {
    this.properties = properties;
    this.adkCoreJarPathForCompilation = locateAndPrepareAdkCoreJar();
  }

  /**
   * Attempts to find the ADK core JAR, which provides {@link BaseAgent} and essential ADK classes
   * required for compiling dynamically loaded agents.
   *
   * <p>Strategies include:
   *
   * <ul>
   *   <li>Checking if {@code BaseAgent.class} is loaded from a plain {@code .jar} file on the
   *       classpath.
   *   <li>Detecting and extracting the ADK core JAR if it's nested within a "fat JAR" (e.g., {@code
   *       BOOT-INF/lib/} in Spring Boot applications). The extracted JAR is placed in a temporary
   *       file for use during compilation.
   * </ul>
   *
   * If located, its absolute path is returned for explicit inclusion in the compiler's classpath.
   * Returns an empty string if the JAR cannot be reliably pinpointed through these specific means,
   * in which case compilation will rely on broader classpath introspection.
   *
   * @return Absolute path to the ADK core JAR if found and prepared, otherwise an empty string.
   */
  private String locateAndPrepareAdkCoreJar() {
    try {
      URL agentClassUrl = BaseAgent.class.getProtectionDomain().getCodeSource().getLocation();
      if (agentClassUrl == null) {
        logger.warn("Could not get location for BaseAgent.class. ADK Core JAR might not be found.");
        return "";
      }
      logger.debug("BaseAgent.class loaded from: {}", agentClassUrl);

      if ("file".equals(agentClassUrl.getProtocol())) {
        Path path = Paths.get(agentClassUrl.toURI());
        if (path.toString().endsWith(".jar") && Files.exists(path)) {
          logger.debug(
              "ADK Core JAR (or where BaseAgent resides) found directly on classpath: {}",
              path.toAbsolutePath());
          return path.toAbsolutePath().toString();
        } else if (Files.isDirectory(path)) {
          logger.debug(
              "BaseAgent.class found in directory (e.g., target/classes): {}. This path will be"
                  + " part of classloader introspection.",
              path.toAbsolutePath());
          return "";
        }
      } else if ("jar".equals(agentClassUrl.getProtocol())) { // Typically for nested JARs
        String urlPath = agentClassUrl.getPath();
        if (urlPath.startsWith("file:")) {
          urlPath = urlPath.substring("file:".length());
        }
        int firstSeparator = urlPath.indexOf("!/");
        if (firstSeparator == -1) {
          logger.warn("Malformed JAR URL for BaseAgent.class: {}", agentClassUrl);
          return "";
        }

        String mainJarPath = urlPath.substring(0, firstSeparator);
        String nestedPath = urlPath.substring(firstSeparator);

        if (nestedPath.startsWith("!/BOOT-INF/lib/") && nestedPath.contains("google-adk-")) {
          int nestedJarStartInPath = "!/BOOT-INF/lib/".length();
          int nestedJarEndInPath = nestedPath.indexOf("!/", nestedJarStartInPath);
          if (nestedJarEndInPath > 0) {
            String nestedJarName = nestedPath.substring(nestedJarStartInPath, nestedJarEndInPath);
            String nestedJarUrlString =
                "jar:file:" + mainJarPath + "!/BOOT-INF/lib/" + nestedJarName;

            Path tempFile = Files.createTempFile("adk-core-extracted-", ".jar");
            try (InputStream is = new URL(nestedJarUrlString).openStream()) {
              Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile.toFile().deleteOnExit();
            logger.debug(
                "Extracted ADK Core JAR '{}' from nested location to: {}",
                nestedJarName,
                tempFile.toAbsolutePath());
            return tempFile.toAbsolutePath().toString();
          }
        } else if (mainJarPath.contains("google-adk-") && mainJarPath.endsWith(".jar")) {
          File adkJar = new File(mainJarPath);
          if (adkJar.exists()) {
            logger.debug("ADK Core JAR identified as the outer JAR: {}", adkJar.getAbsolutePath());
            return adkJar.getAbsolutePath();
          }
        }
      }
    } catch (Exception e) {
      logger.error("Error trying to locate or extract ADK Core JAR", e);
    }
    logger.warn(
        "ADK Core JAR could not be reliably located for compilation via locateAndPrepareAdkCoreJar."
            + " Relying on classloader introspection.");
    return "";
  }

  /**
   * Discovers, compiles, and loads agents from the configured source directory.
   *
   * <p>The process for each potential "agent unit" (a subdirectory or a root {@code .java} file):
   *
   * <ol>
   *   <li>Collects {@code .java} source files.
   *   <li>Compiles these sources using ECJ (see {@link #compileSourcesWithECJ(List, Path)}) into a
   *       temporary, unit-specific output directory. This directory is cleaned up on JVM exit.
   *   <li>Creates a dedicated {@link URLClassLoader} for the compiled unit, isolating its classes.
   *   <li>Scans compiled classes for a public static field {@code ROOT_AGENT} assignable to {@link
   *       BaseAgent}. This field serves as the designated entry point for an agent.
   *   <li>Instantiates and stores the {@link BaseAgent} if found, keyed by its name.
   * </ol>
   *
   * This approach allows for dynamic addition of agents without pre-compilation and supports
   * independent classpaths per agent unit if needed (though current implementation uses a shared
   * parent classloader).
   *
   * @return A map of successfully loaded agent names to their {@link BaseAgent} instances. Returns
   *     an empty map if the source directory isn't configured or no agents are found.
   * @throws IOException If an I/O error occurs (e.g., creating temp directories, reading sources).
   */
  public Map<String, BaseAgent> loadAgents() throws IOException {
    if (properties.getSourceDir() == null || properties.getSourceDir().isEmpty()) {
      logger.info(
          "Agent source directory (adk.agents.source-dir) not configured. No dynamic agents will be"
              + " loaded.");
      return Collections.emptyMap();
    }

    Path agentsSourceRoot = Paths.get(properties.getSourceDir());
    if (!Files.isDirectory(agentsSourceRoot)) {
      logger.warn("Agent source directory does not exist: {}", agentsSourceRoot);
      return Collections.emptyMap();
    }

    this.compiledAgentsOutputDir = Files.createTempDirectory("adk-compiled-agents-");
    this.compiledAgentsOutputDir.toFile().deleteOnExit();
    logger.debug("Compiling agents from {} to {}", agentsSourceRoot, compiledAgentsOutputDir);

    Map<String, BaseAgent> loadedAgents = new HashMap<>();

    try (Stream<Path> stream = Files.list(agentsSourceRoot)) {
      List<Path> entries = stream.collect(Collectors.toList());

      for (Path entry : entries) {
        List<String> javaFilesToCompile = new ArrayList<>();
        String agentUnitName;

        if (Files.isDirectory(entry)) {
          agentUnitName = entry.getFileName().toString();
          logger.debug("Processing agent sources from directory: {}", agentUnitName);
          try (Stream<Path> javaFilesStream =
              Files.walk(entry)
                  .filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))) {
            javaFilesToCompile =
                javaFilesStream
                    .map(p -> p.toAbsolutePath().toString())
                    .collect(Collectors.toList());
          }
        } else if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".java")) {
          String fileName = entry.getFileName().toString();
          agentUnitName = fileName.substring(0, fileName.length() - ".java".length());
          logger.debug("Processing agent source file: {}", entry.getFileName());
          javaFilesToCompile.add(entry.toAbsolutePath().toString());
        } else {
          logger.trace("Skipping non-agent entry in agent source root: {}", entry.getFileName());
          continue;
        }

        if (javaFilesToCompile.isEmpty()) {
          logger.debug("No .java files found for agent unit: {}", agentUnitName);
          continue;
        }

        Path unitSpecificOutputDir = compiledAgentsOutputDir.resolve(agentUnitName);
        Files.createDirectories(unitSpecificOutputDir);

        boolean compilationSuccess =
            compileSourcesWithECJ(javaFilesToCompile, unitSpecificOutputDir);

        if (compilationSuccess) {
          try {
            List<URL> classLoaderUrls = new ArrayList<>();
            classLoaderUrls.add(unitSpecificOutputDir.toUri().toURL());

            URLClassLoader agentClassLoader =
                new URLClassLoader(
                    classLoaderUrls.toArray(new URL[0]),
                    AgentCompilerLoader.class.getClassLoader());

            Files.walk(unitSpecificOutputDir)
                .filter(p -> p.toString().endsWith(".class"))
                .forEach(
                    classFile -> {
                      try {
                        String relativePath =
                            unitSpecificOutputDir.relativize(classFile).toString();
                        String className =
                            relativePath
                                .substring(0, relativePath.length() - ".class".length())
                                .replace(File.separatorChar, '.');

                        Class<?> loadedClass = agentClassLoader.loadClass(className);
                        Field rootAgentField = null;
                        try {
                          rootAgentField = loadedClass.getField("ROOT_AGENT");
                        } catch (NoSuchFieldException e) {
                          return;
                        }

                        if (Modifier.isStatic(rootAgentField.getModifiers())
                            && BaseAgent.class.isAssignableFrom(rootAgentField.getType())) {
                          BaseAgent agentInstance = (BaseAgent) rootAgentField.get(null);
                          if (agentInstance != null) {
                            if (loadedAgents.containsKey(agentInstance.name())) {
                              logger.warn(
                                  "Found another agent with name {}. This will overwrite the"
                                      + " original agent loaded with this name from unit {} using"
                                      + " class {}",
                                  agentInstance.name(),
                                  agentUnitName,
                                  className);
                            }
                            loadedAgents.put(agentInstance.name(), agentInstance);
                            logger.debug(
                                "Successfully loaded agent '{}' from unit: {} using class {}",
                                agentInstance.name(),
                                agentUnitName,
                                className);
                          } else {
                            logger.warn(
                                "ROOT_AGENT field in class {} from unit {} was null",
                                className,
                                agentUnitName);
                          }
                        }
                      } catch (ClassNotFoundException | IllegalAccessException e) {
                        logger.error(
                            "Error loading or accessing agent from class file {} for unit {}",
                            classFile,
                            agentUnitName,
                            e);
                      } catch (Exception e) {
                        logger.error(
                            "Unexpected error processing class file {} for unit {}",
                            classFile,
                            agentUnitName,
                            e);
                      }
                    });
          } catch (Exception e) {
            logger.error(
                "Error during class loading setup for unit {}: {}",
                agentUnitName,
                e.getMessage(),
                e);
          }
        } else {
          logger.error("Compilation failed for agent unit: {}", agentUnitName);
        }
      }
    }
    return loadedAgents;
  }

  /**
   * Compiles the given Java source files using the Eclipse JDT (ECJ) batch compiler.
   *
   * <p>Key aspects of the compilation process:
   *
   * <ul>
   *   <li>Sets Java version to 17 (to align with core ADK library) and suppresses warnings by
   *       default.
   *   <li>Constructs the compilation classpath by:
   *       <ol>
   *         <li>Introspecting the current classloader hierarchy ({@link URLClassLoader} instances)
   *             to gather available JARs and class directories.
   *         <li>Falling back to {@code System.getProperty("java.class.path")} if introspection
   *             yields no results.
   *         <li>Explicitly adding the ADK Core JAR path (determined by {@link
   *             #locateAndPrepareAdkCoreJar()}) to ensure {@link BaseAgent} and related types are
   *             resolvable.
   *         <li>Appending any user-defined classpath entries from {@link
   *             AgentLoadingProperties#getCompileClasspath()}.
   *       </ol>
   *   <li>Outputs compiled {@code .class} files to the specified {@code outputDir}.
   * </ul>
   *
   * This method aims to provide a robust classpath for compiling agents in various runtime
   * environments, including IDEs, standard Java executions, and fat JAR deployments.
   *
   * @param javaFilePaths A list of absolute paths to {@code .java} files to be compiled.
   * @param outputDir The directory where compiled {@code .class} files will be placed.
   * @return {@code true} if compilation succeeds, {@code false} otherwise.
   */
  private boolean compileSourcesWithECJ(List<String> javaFilePaths, Path outputDir) {
    List<String> ecjArgs = new ArrayList<>();
    ecjArgs.add("-17"); // Java version
    ecjArgs.add("-nowarn");
    ecjArgs.add("-d");
    ecjArgs.add(outputDir.toAbsolutePath().toString());

    Set<String> classpathEntries = new LinkedHashSet<>();

    logger.debug("Attempting to derive ECJ classpath from classloader hierarchy...");
    ClassLoader currentClassLoader = AgentCompilerLoader.class.getClassLoader();
    int classLoaderCount = 0;
    while (currentClassLoader != null) {
      classLoaderCount++;
      logger.debug(
          "Inspecting classloader ({}) : {}",
          classLoaderCount,
          currentClassLoader.getClass().getName());
      if (currentClassLoader instanceof java.net.URLClassLoader) {
        URL[] urls = ((java.net.URLClassLoader) currentClassLoader).getURLs();
        logger.debug(
            "  Found {} URLs in URLClassLoader {}",
            urls.length,
            currentClassLoader.getClass().getName());
        for (URL url : urls) {
          try {
            if ("file".equals(url.getProtocol())) {
              String path = Paths.get(url.toURI()).toString();
              classpathEntries.add(path);
              logger.trace("    Added to ECJ classpath: {}", path);
            } else {
              logger.debug(
                  "    Skipping non-file URL from classloader {}: {}",
                  currentClassLoader.getClass().getName(),
                  url);
            }
          } catch (URISyntaxException | IllegalArgumentException e) {
            logger.warn(
                "    Could not convert URL to path or add to classpath from {}: {} (Error: {})",
                currentClassLoader.getClass().getName(),
                url,
                e.getMessage());
          } catch (Exception e) {
            logger.warn(
                "    Unexpected error converting URL to path from {}: {} (Error: {})",
                currentClassLoader.getClass().getName(),
                url,
                e.getMessage(),
                e);
          }
        }
      }
      currentClassLoader = currentClassLoader.getParent();
    }

    if (classpathEntries.isEmpty()) {
      logger.warn(
          "No classpath entries derived from classloader hierarchy. "
              + "Falling back to System.getProperty(\"java.class.path\").");
      String systemClasspath = System.getProperty("java.class.path");
      if (systemClasspath != null && !systemClasspath.isEmpty()) {
        logger.debug("Using system classpath for ECJ (fallback): {}", systemClasspath);
        classpathEntries.addAll(Arrays.asList(systemClasspath.split(File.pathSeparator)));
      } else {
        logger.error("System classpath (java.class.path) is also null or empty.");
      }
    }

    if (this.adkCoreJarPathForCompilation != null && !this.adkCoreJarPathForCompilation.isEmpty()) {
      if (!classpathEntries.contains(this.adkCoreJarPathForCompilation)) {
        logger.debug(
            "Adding ADK Core JAR path explicitly to ECJ classpath: {}",
            this.adkCoreJarPathForCompilation);
        classpathEntries.add(this.adkCoreJarPathForCompilation);
      } else {
        logger.debug(
            "ADK Core JAR path ({}) already found in derived ECJ classpath.",
            this.adkCoreJarPathForCompilation);
      }
    } else if (classpathEntries.stream().noneMatch(p -> p.contains("google-adk"))) {
      logger.error(
          "ADK Core JAR path is missing and no 'google-adk' JAR found in derived classpath. "
              + "Compilation will likely fail to find BaseAgent.");
    }

    if (properties.getCompileClasspath() != null && !properties.getCompileClasspath().isEmpty()) {
      String userClasspath = properties.getCompileClasspath();
      logger.info(
          "Appending user-defined classpath (adk.agents.compile-classpath) to ECJ: {}",
          userClasspath);
      classpathEntries.addAll(Arrays.asList(userClasspath.split(File.pathSeparator)));
    }

    if (!classpathEntries.isEmpty()) {
      String effectiveClasspath =
          classpathEntries.stream().collect(Collectors.joining(File.pathSeparator));
      ecjArgs.add("-cp");
      ecjArgs.add(effectiveClasspath);
      logger.debug("Constructed ECJ classpath with {} entries", classpathEntries.size());
      logger.debug("Final effective ECJ classpath: {}", effectiveClasspath);
    } else {
      logger.error("ECJ Classpath is empty after all attempts. Compilation will fail.");
      return false;
    }

    ecjArgs.addAll(javaFilePaths);

    logger.debug("ECJ Args: {}", String.join(" ", ecjArgs));

    PrintWriter outWriter = new PrintWriter(System.out, true);
    PrintWriter errWriter = new PrintWriter(System.err, true);

    boolean success =
        BatchCompiler.compile(ecjArgs.toArray(new String[0]), outWriter, errWriter, null);
    if (!success) {
      logger.error("ECJ Compilation failed. See console output for details.");
    }
    return success;
  }
}
