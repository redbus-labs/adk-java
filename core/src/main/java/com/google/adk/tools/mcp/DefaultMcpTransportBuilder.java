package com.google.adk.tools.mcp;

import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.Collection;
import java.util.Optional;

/**
 * The default builder for creating MCP client transports. Supports StdioClientTransport based on
 * {@link ServerParameters}, HttpClientSseClientTransport based on {@link SseServerParameters}, and
 * (temporarily) maps {@link StreamableHttpServerParameters} to HttpClientSseClientTransport until a
 * dedicated streamable HTTP transport becomes available in the MCP SDK version in use.
 */
public class DefaultMcpTransportBuilder implements McpTransportBuilder {

  @Override
  public McpClientTransport build(Object connectionParams) {
    if (connectionParams instanceof ServerParameters serverParameters) {
      return new StdioClientTransport(serverParameters);
    } else if (connectionParams instanceof SseServerParameters sseServerParams) {
      return HttpClientSseClientTransport.builder(sseServerParams.url())
          .sseEndpoint(
              sseServerParams.sseEndpoint() == null ? "sse" : sseServerParams.sseEndpoint())
          .customizeRequest(
              builder ->
                  Optional.ofNullable(sseServerParams.headers())
                      .map(ImmutableMap::entrySet)
                      .stream()
                      .flatMap(Collection::stream)
                      .forEach(
                          entry ->
                              builder.header(
                                  entry.getKey(),
                                  Optional.ofNullable(entry.getValue())
                                      .map(Object::toString)
                                      .orElse(""))))
          .build();
    } else if (connectionParams instanceof StreamableHttpServerParameters streamableParams) {
      // Fallback: use SSE transport for streamable parameters until streamable HTTP transport
      // class is present in the dependency.
      return HttpClientSseClientTransport.builder(streamableParams.url())
          .sseEndpoint("sse")
          .customizeRequest(
              builder -> {
                streamableParams.headers().forEach(builder::header);
                // no return; functional interface is likely a Consumer
              })
          .build();
    } else {
      throw new IllegalArgumentException(
          "DefaultMcpTransportBuilder supports only ServerParameters, SseServerParameters, or"
              + " StreamableHttpServerParameters, but got "
              + connectionParams.getClass().getName());
    }
  }
}
