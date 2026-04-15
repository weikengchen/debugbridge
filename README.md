# DebugBridge

A Lua-Java bridge for Minecraft that enables AI agents and developers to inspect and interact with a running game client through a WebSocket interface.

## What It Does

DebugBridge exposes a localhost-only WebSocket server (default port 9876) that accepts Lua scripts for execution inside the Minecraft JVM. This allows external tools to:

- **Inspect game state** - Query player position, health, inventory, nearby entities, block data, etc.
- **Call Minecraft APIs** - Invoke any method on any Minecraft class using human-readable Mojang names
- **Automate testing** - Script complex game scenarios for mod development or QA
- **Build AI integrations** - Enable AI assistants to understand and interact with the game world

The primary use case is integration with [Claude Code](https://claude.ai/claude-code) and other AI coding assistants via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/).

> **Note:** The MCP server has been merged into [mcdev-mcp](https://github.com/weikengchen/mcdev-mcp), which provides both runtime interaction tools (from DebugBridge) and static code analysis tools (decompiled source search, call graphs, etc.). This repository now contains only the Minecraft Fabric mod.

## How It Works

### Architecture

```
+-------------------------------------------------------------+
|  AI Agent / Developer Tool                                   |
|  (Claude Code, custom scripts, etc.)                        |
+---------------------+---------------------------------------+
                      | MCP Protocol (stdio)
+---------------------v---------------------------------------+
|  mcdev-mcp Server (TypeScript)                               |
|  - Runtime tools: mc_execute, mc_snapshot, mc_screenshot    |
|  - Static tools: mc_get_class, mc_search_source, mc_find_refs|
|  - See: https://github.com/weikengchen/mcdev-mcp            |
+---------------------+---------------------------------------+
                      | WebSocket (localhost:9876)
+---------------------v---------------------------------------+
|  DebugBridge Mod (inside Minecraft JVM) [THIS REPOSITORY]    |
|  - LuaJ interpreter with Java reflection bridge             |
|  - Mojang mapping resolver (auto-downloaded)                |
|  - Thread dispatcher for game-thread safety                 |
+-------------------------------------------------------------+
```

### Lua Environment

Scripts execute in a sandboxed Lua 5.2 environment (via LuaJ) with a `java` global table providing:

```lua
-- Import and instantiate classes
local Minecraft = java.import("net.minecraft.client.Minecraft")
local mc = Minecraft:getInstance()

-- Access fields and call methods using Mojang names
local player = mc.player
local pos = player:blockPosition()
print("Player at: " .. pos:getX() .. ", " .. pos:getY() .. ", " .. pos:getZ())

-- Iterate Java collections
for entity in java.iter(mc.level:entitiesForRendering()) do
    if entity:distanceTo(player) < 10 then
        print("Nearby: " .. java.typeof(entity))
    end
end
```

### Mojang Mapping Support

The mod automatically downloads official Mojang mappings at startup and uses them to translate human-readable names (like `net.minecraft.client.Minecraft`) to the obfuscated names used at runtime. This means:

- Scripts use stable, documented Mojang names regardless of Minecraft version
- No need to learn or track obfuscated intermediary names
- Code is more readable and maintainable

## Security Model

**DebugBridge binds exclusively to localhost (127.0.0.1).** This is enforced in the server initialization:

```java
// From BridgeServer.java
super(new InetSocketAddress("127.0.0.1", port));
```

This means:
- Only processes running on the same machine can connect
- The debug port is not accessible from the network
- Remote machines cannot connect even if they know the port

This is intentional: DebugBridge is a **development and debugging tool**, not a remote administration system. Anyone with localhost access already has full control over the Minecraft process (they could attach a debugger, modify memory, etc.), so the bridge does not introduce new attack surface.

### Additional Considerations

- **Client-side only**: The mod runs entirely on the client. It cannot affect servers or other players.
- **No auto-start networking**: The WebSocket server only starts when the mod loads. It does not phone home or connect to external services.
- **No persistence**: The Lua environment resets when the game restarts. Scripts cannot persist data or install hooks that survive restarts (unless the agent module is used, which requires explicit JVM flags).

## Included Components

### 1. Fabric Mod (`mod/`)

The core mod that runs inside Minecraft:
- `core/` - Shared code: Lua runtime, WebSocket server, mapping resolver
- `fabric-1.19/` - Fabric mod for Minecraft 1.19.x
- `fabric-1.21.11/` - Fabric mod for Minecraft 1.21.11

### 2. Agent Module (`mod/agent/`, `mod/hooks/`)

Optional runtime instrumentation for advanced debugging:
- Inject logging into arbitrary methods at runtime
- Uses Java Agent + Byte Buddy for bytecode modification
- Mixin-compatible (preserves existing transformations)
- Requires `-javaagent` JVM flag to enable

## Building

```bash
# Build the Fabric mods
cd mod
./gradlew build
```

Output JARs are in `mod/fabric-*/build/libs/`.

## Usage

### With Claude Code / MCP

See [mcdev-mcp](https://github.com/weikengchen/mcdev-mcp) for the MCP server setup. The combined server provides:
- **Runtime tools** (require this mod running): `mc_connect`, `mc_execute`, `mc_snapshot`, `mc_screenshot`, `mc_run_command`, `mc_search_mappings`
- **Static tools** (work offline): `mc_get_class`, `mc_get_method`, `mc_search_source`, `mc_find_refs`, `mc_find_hierarchy`

### Direct WebSocket

Connect to `ws://127.0.0.1:9876` and send JSON messages:

```json
{
  "id": "1",
  "type": "execute",
  "payload": {
    "code": "return java.import('net.minecraft.client.Minecraft'):getInstance().player:blockPosition():toShortString()"
  }
}
```

## Source Code

The complete source code is available at: https://github.com/weikengchen/debugbridge

## License

MIT License - see LICENSE file for details.

## Technical Details for Review

### Dependencies Bundled in JAR

The Fabric mod JARs include these libraries (shaded):
- **LuaJ 3.0.1** - Pure Java Lua 5.2 implementation (MIT license)
- **Java-WebSocket 1.5.7** - WebSocket server (MIT license)
- **Gson 2.11.0** - JSON parsing (Apache 2.0 license)

### Network Behavior

- **Outbound**: One HTTPS request at startup to download Mojang mappings from `piston-meta.mojang.com` and `launcher.mojang.com` (official Mojang APIs)
- **Inbound**: WebSocket server on localhost:9876 (configurable via `DEBUGBRIDGE_PORT` environment variable)
- **No telemetry, analytics, or other network activity**

### File System Access

- **Reads**: Mojang mapping files (cached in game directory)
- **Writes**: 
  - Cached mappings in game directory
  - Screenshots to temp directory (when requested via MCP)
  - Log files (when agent module is used)

### Thread Safety

All Lua execution that touches Minecraft state is dispatched to the main game thread via a synchronized queue. The WebSocket server runs on a separate thread but never directly accesses game state.

### Why Lua?

Lua was chosen because:
1. LuaJ provides a pure-Java implementation with no native code
2. Lua's syntax is simple and readable for non-programmers
3. The interpreter is lightweight (~300KB) and starts instantly
4. Sandboxing is straightforward (we expose only the `java` table)

### Obfuscation Handling

Minecraft's code is obfuscated differently across versions:
- **1.19.x**: Uses Fabric's intermediary names at runtime, requires mapping
- **1.21.11+**: Mojang removed obfuscation, names match directly

The mod detects which environment it's running in and applies mappings only when needed.
