# Cassandra Implementation

**Author:** Sandeep Belgavi
**Date:** October 19, 2025

## Overview

This document outlines the Cassandra-backed implementation for the memory service within the Google Agent Development Kit (ADK). This implementation provides a persistent storage layer for vector embeddings, enabling Approximate Nearest Neighbor (ANN) search capabilities for agents using Apache Cassandra.

## Core Components

The architecture consists of the following key components:

-   **`CassandraRunner`**: This is the entry point for running an agent with the Cassandra backend. It initializes the `CassandraHelper` and injects the `CassandraMemoryService` into the agent runner.

-   **`CassandraMemoryService`**: This service implements the `BaseMemoryService` interface. It uses the `CassandraRagRetrieval` tool to perform ANN searches against the Cassandra database.

-   **`CassandraRagRetrieval`**: This tool is responsible for executing the actual ANN query against the `rae_data` table in Cassandra. It uses the `similarity_cosine` function to find the most similar vectors and filters the results based on a similarity threshold.

-   **`CassandraHelper`**: This is a singleton class that manages the connection to the Cassandra database. It is responsible for initializing the `CqlSession` and creating the `rae` keyspace and `rae_data` table if they don't exist.

## Cassandra Schema

The following CQL statements are used to create the necessary keyspace and table in Cassandra. These are executed automatically by the `CassandraHelper` class upon initialization.

```cql
CREATE KEYSPACE IF NOT EXISTS rae WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE rae;

CREATE TABLE IF NOT EXISTS rae_data (
    client_id TEXT,
    session_id TEXT,
    data TEXT,
    embedding VECTOR<FLOAT, 768>,
    PRIMARY KEY (client_id, session_id)
);

CREATE CUSTOM INDEX IF NOT EXISTS ON rae_data (embedding) USING 'org.apache.cassandra.index.sai.StorageAttachedIndex' WITH OPTIONS = {'similarity_function': 'COSINE'};
```

**Table Explanation:**

*   `client_id`: The identifier for the client or user.
*   `session_id`: The identifier for the session.
*   `data`: A text field to store any additional data associated with the embedding.
*   `embedding`: A `vector` column to store the vector embeddings. The example above uses a dimension of 768, but this should be adjusted to match the dimension of your embeddings.
*   **SAI Index:** A Storage-Attached Index is required on the `embedding` column to enable ANN search. The `similarity_function` is set to `COSINE` to calculate the similarity between vectors.

## Integration and Usage

The `CassandraRunner` is the recommended way to run an agent with the Cassandra-backed memory service. The runner will automatically initialize the Cassandra connection and provide the `CassandraMemoryService` to the agent.

### Example: Running an Agent with `CassandraRunner`

The following example demonstrates how to create a simple agent and run it with the `CassandraRunner`.

```java
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.CassandraRunner;
import com.google.adk.runner.Runner;
import com.google.adk.store.CassandraHelper;
import java.net.InetSocketAddress;

public class CassandraRunnerExample {
    public static void main(String[] args) {
        // Initialize Cassandra connection
        CqlSessionBuilder sessionBuilder = CqlSession.builder()
            .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
            .withLocalDatacenter("datacenter1");
        CassandraHelper.initialize(sessionBuilder);

        // Create an agent
        LlmAgent agent = LlmAgent.builder()
            .name("my-agent")
            .description("An agent that uses Cassandra for memory")
            .build();

        // Create a CassandraRunner
        Runner runner = new CassandraRunner(agent);

        // The runner will automatically use CassandraMemoryService.
        // You can now run the agent as usual.
        // runner.runLive(appName, userId, sessionId, System.in, System.out);

        CassandraHelper.close();
    }
}
```

### `curl` Command for ANN Search

You can use the following `curl` command to test the ANN search functionality. This command sends a JSON-RPC 2.0 request to the agent, which then uses the `CassandraRagRetrieval` tool to perform the ANN search.

```bash
curl -X POST \
  http://127.0.0.1:8080/ \
  -H 'Content-Type: application/json' \
  -d 
    "jsonrpc": "2.0",
    "id": "req-001",
    "method": "agent.run",
    "params": {
      "app_name": "myApp",
      "user_id": "user123",
      "session_id": "session123",
      "message": {
        "parts": [
          {
            "text": "Find similar routes"
          }
        ]
      },
      "embedding": [0.1, 0.2, 0.3, ...],
      "keyspace": "rae",
      "table": "rae_data",
      "embedding_column": "embedding",
      "similarity_threshold": 0.85
    }
  
```

**Note:** You will need to replace the `embedding` with your actual embedding vector.