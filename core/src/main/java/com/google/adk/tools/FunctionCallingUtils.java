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

package com.google.adk.tools;

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.google.adk.JsonBaseModel;
import com.google.common.base.Strings;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Utility class for function calling. */
public final class FunctionCallingUtils {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static FunctionDeclaration buildFunctionDeclaration(Method func, List<String> ignoreParams) {
    String name =
        func.isAnnotationPresent(Annotations.Schema.class)
                && !func.getAnnotation(Annotations.Schema.class).name().isEmpty()
            ? func.getAnnotation(Annotations.Schema.class).name()
            : func.getName();
    FunctionDeclaration.Builder builder = FunctionDeclaration.builder().name(name);
    if (func.isAnnotationPresent(Annotations.Schema.class)
        && !func.getAnnotation(Annotations.Schema.class).description().isEmpty()) {
      builder.description(func.getAnnotation(Annotations.Schema.class).description());
    }
    List<String> required = new ArrayList<>();
    Map<String, Schema> properties = new LinkedHashMap<>();
    for (Parameter param : func.getParameters()) {
      String paramName =
          param.isAnnotationPresent(Annotations.Schema.class)
                  && !param.getAnnotation(Annotations.Schema.class).name().isEmpty()
              ? param.getAnnotation(Annotations.Schema.class).name()
              : param.getName();
      if (ignoreParams.contains(paramName)) {
        continue;
      }
      required.add(paramName);
      properties.put(paramName, buildSchemaFromParameter(param));
    }
    builder.parameters(
        Schema.builder().required(required).properties(properties).type("OBJECT").build());

    Type returnType = func.getGenericReturnType();
    if (returnType != Void.TYPE) {
      Type realReturnType = returnType;
      if (returnType instanceof ParameterizedType) {
        ParameterizedType parameterizedReturnType = (ParameterizedType) returnType;
        String returnTypeName = ((Class<?>) parameterizedReturnType.getRawType()).getName();
        if (returnTypeName.equals("io.reactivex.rxjava3.core.Maybe")
            || returnTypeName.equals("io.reactivex.rxjava3.core.Single")) {
          returnType = parameterizedReturnType.getActualTypeArguments()[0];
          if (returnType instanceof ParameterizedType) {
            ParameterizedType maybeParameterizedType = (ParameterizedType) returnType;
            returnTypeName = ((Class<?>) maybeParameterizedType.getRawType()).getName();
          }
        }
        if (returnTypeName.equals("java.util.Map")
            || returnTypeName.equals("com.google.common.collect.ImmutableMap")) {
          return builder.response(buildSchemaFromType(returnType)).build();
        }
      }
      throw new IllegalArgumentException(
          "Return type should be Map or Maybe<Map> or Single<Map>, but it was "
              + realReturnType.getTypeName());
    }
    return builder.build();
  }

  static FunctionDeclaration buildFunctionDeclaration(JsonBaseModel func, String description) {
    // Create function declaration through json string.
    String jsonString = func.toJson();
    checkArgument(!Strings.isNullOrEmpty(jsonString), "Input String can't be null or empty.");
    FunctionDeclaration declaration = FunctionDeclaration.fromJson(jsonString);
    declaration = declaration.toBuilder().description(description).build();
    if (declaration.name().isEmpty() || declaration.name().get().isEmpty()) {
      throw new IllegalArgumentException("name field must be present.");
    }
    return declaration;
  }

  private static Schema buildSchemaFromParameter(Parameter param) {
    Schema.Builder builder = Schema.builder();
    if (param.isAnnotationPresent(Annotations.Schema.class)
        && !param.getAnnotation(Annotations.Schema.class).description().isEmpty()) {
      builder.description(param.getAnnotation(Annotations.Schema.class).description());
    }
    switch (param.getType().getName()) {
      case "java.lang.String" -> builder.type("STRING");
      case "boolean", "java.lang.Boolean" -> builder.type("BOOLEAN");
      case "int", "java.lang.Integer" -> builder.type("INTEGER");
      case "double", "java.lang.Double", "float", "java.lang.Float", "long", "java.lang.Long" ->
          builder.type("NUMBER");
      case "java.util.List" ->
          builder
              .type("ARRAY")
              .items(
                  buildSchemaFromType(
                      ((ParameterizedType) param.getParameterizedType())
                          .getActualTypeArguments()[0]));
      case "java.util.Map" -> builder.type("OBJECT");
      default -> {
        BeanDescription beanDescription =
            OBJECT_MAPPER
                .getSerializationConfig()
                .introspect(OBJECT_MAPPER.constructType(param.getType()));
        Map<String, Schema> properties = new LinkedHashMap<>();
        for (BeanPropertyDefinition property : beanDescription.findProperties()) {
          properties.put(property.getName(), buildSchemaFromType(property.getRawPrimaryType()));
        }
        builder.type("OBJECT").properties(properties);
      }
    }
    return builder.build();
  }

  public static Schema buildSchemaFromType(Type type) {
    Schema.Builder builder = Schema.builder();
    if (type instanceof ParameterizedType parameterizedType) {
      switch (((Class<?>) parameterizedType.getRawType()).getName()) {
        case "java.util.List" ->
            builder
                .type("ARRAY")
                .items(buildSchemaFromType(parameterizedType.getActualTypeArguments()[0]));
        case "java.util.Map", "com.google.common.collect.ImmutableMap" -> builder.type("OBJECT");
        default -> throw new IllegalArgumentException("Unsupported generic type: " + type);
      }
    } else if (type instanceof Class<?> clazz) {
      switch (clazz.getName()) {
        case "java.lang.String" -> builder.type("STRING");
        case "boolean", "java.lang.Boolean" -> builder.type("BOOLEAN");
        case "int", "java.lang.Integer" -> builder.type("INTEGER");
        case "double", "java.lang.Double", "float", "java.lang.Float", "long", "java.lang.Long" ->
            builder.type("NUMBER");
        case "java.util.Map", "com.google.common.collect.ImmutableMap" -> builder.type("OBJECT");
        default -> {
          BeanDescription beanDescription =
              OBJECT_MAPPER.getSerializationConfig().introspect(OBJECT_MAPPER.constructType(type));
          Map<String, Schema> properties = new LinkedHashMap<>();
          for (BeanPropertyDefinition property : beanDescription.findProperties()) {
            properties.put(property.getName(), buildSchemaFromType(property.getRawPrimaryType()));
          }
          builder.type("OBJECT").properties(properties);
        }
      }
    }
    return builder.build();
  }

  private FunctionCallingUtils() {}
}
