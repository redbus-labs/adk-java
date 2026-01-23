#!/bin/bash

# Script to open PR creation page with pre-filled data
# Author: Sandeep Belgavi
# Date: January 24, 2026

PR_URL="https://github.com/redbus-labs/adk-java/compare/main...sse?expand=1"

PR_TITLE="feat: SSE implementation with HttpServer (default) and Spring (alternative)"

PR_BODY=$(cat <<'EOF'
## Summary

Implements Server-Sent Events (SSE) streaming with two options:
1. **HttpServer SSE (Default)** - Port 9085 - Zero dependencies, lightweight, best performance
2. **Spring SSE (Alternative)** - Port 9086 - Rich ecosystem, enterprise features

## Changes

- HttpServer SSE endpoint (`/run_sse`) on port 9085 (default)
- Spring SSE endpoint (`/run_sse_spring`) on port 9086
- Fixed JSON parsing: Changed from Gson to Jackson ObjectMapper
- Comprehensive guide with pros/cons and usage instructions
- Unit and integration tests

## Documentation

See `dev/SSE_GUIDE.md` for:
- Pros/cons of HttpServer vs Spring SSE
- When to use each option
- Usage instructions and examples
- Configuration guide

## Testing

```bash
# Test HttpServer SSE (port 9085)
curl -N -X POST http://localhost:9085/run_sse \
  -H "Content-Type: application/json" \
  -d @dev/test_request.json

# Test Spring SSE (port 9086)
curl -N -X POST http://localhost:9086/run_sse_spring \
  -H "Content-Type: application/json" \
  -d @dev/test_request.json
```

## Files Changed

- 19 new files (SSE implementation, tests, documentation)
- 1 modified file (`ExecutionController.java`)
- Total: +4260 insertions, -136 deletions

Author: Sandeep Belgavi
Date: January 24, 2026
EOF
)

echo "Opening PR creation page..."
echo ""
echo "Base: main"
echo "Compare: sse"
echo ""
echo "URL: $PR_URL"
echo ""

# Try to open in browser
if command -v open >/dev/null 2>&1; then
    # macOS
    open "$PR_URL"
elif command -v xdg-open >/dev/null 2>&1; then
    # Linux
    xdg-open "$PR_URL"
elif command -v start >/dev/null 2>&1; then
    # Windows
    start "$PR_URL"
else
    echo "Please open this URL in your browser:"
    echo "$PR_URL"
fi

echo ""
echo "PR Title:"
echo "$PR_TITLE"
echo ""
echo "PR Body (copy this):"
echo "---"
echo "$PR_BODY"
echo "---"
