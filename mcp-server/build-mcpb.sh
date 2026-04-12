#!/usr/bin/env bash
# Build a Claude Desktop MCPB bundle from the MCP server sources.
#
# Usage: ./build-mcpb.sh
#
# Output: ./minecraft-debug-bridge.mcpb
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

STAGING="$HERE/mcpb"
REPO_ROOT="$(cd "$HERE/.." && pwd)"

# 1. Install dev deps and compile TypeScript.
#    --noEmitOnError false because current @modelcontextprotocol/sdk + zod
#    type inference hits TS2589 but still emits valid runtime JS.
if [ ! -d node_modules ]; then
    npm install --no-audit --no-fund
fi
npx tsc --noEmitOnError false || true

if [ ! -f dist/index.js ]; then
    echo "error: dist/index.js not emitted; TypeScript compile failed hard." >&2
    exit 1
fi

# 2. Populate the staging directory with the generated server files.
mkdir -p "$STAGING/server"
cp dist/index.js dist/session.js dist/tools.js dist/types.js "$STAGING/server/"

# 3. Render a PNG icon from the repo SVG (manifest references icon.png).
if command -v rsvg-convert >/dev/null 2>&1; then
    rsvg-convert -w 512 -h 512 "$REPO_ROOT/icon.svg" -o "$STAGING/icon.png"
elif command -v sips >/dev/null 2>&1; then
    sips -s format png -z 512 512 "$REPO_ROOT/icon.svg" --out "$STAGING/icon.png" >/dev/null
else
    echo "warning: no SVG→PNG converter found (rsvg-convert, sips). Skipping icon." >&2
fi

# 4. Install production-only deps into the staging dir.
( cd "$STAGING" && npm install --omit=dev --no-audit --no-fund )

# 5. Validate and pack.
mcpb validate "$STAGING/manifest.json"
mcpb pack "$STAGING" "$HERE/minecraft-debug-bridge.mcpb"

echo
echo "Built: $HERE/minecraft-debug-bridge.mcpb"
