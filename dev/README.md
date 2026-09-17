# ADK Development Utilities

ADK development utilities such as Spring REST server for agent.

**Author**: Sandeep Belgavi
**Date**: September 17, 2026

## Serving the dev UI

The UI and its assets are served under `/dev-ui/`, and both `/` and `/dev-ui`
redirect there, keeping the query string. The assets are not served from the
origin root: `/adk_favicon.svg` and the like return 404, and only the `/dev-ui/`
form resolves.

## Telemetry Export for JDBC

This document describes how to capture telemetry data for JDBC calls.

### Configuration

The application uses OpenTelemetry to capture telemetry data. To capture JDBC telemetry, you need to use the OpenTelemetry Java Agent. The agent automatically instruments JDBC calls and exports the data to your configured OpenTelemetry backend.

No code changes are required to enable JDBC telemetry.

For more information on how to configure the OpenTelemetry Java Agent, please refer to the [OpenTelemetry documentation](https://opentelemetry.io/docs/instrumentation/java/automatic/).
