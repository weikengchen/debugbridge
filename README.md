# DebugBridge

A Fabric client mod that opens a WebSocket bridge into a running Minecraft instance and lets you drive the game from outside it with Lua. Class, field, and method names use Mojang's official mapping names regardless of the Minecraft version you're playing — the mod downloads and parses the official ProGuard mappings the first time it starts and resolves obfuscated names transparently.

It is built for three audiences:

- **Mod developers** poking at the live game to figure out how a class behaves before writing a mixin.
- **Power users / scripters** who want a real REPL for the Minecraft client without recompiling anything.
- **AI agents** that need a programmatic interface to inspect and manipulate game state. The repo ships an optional [Model Context Protocol](https://modelcontextprotocol.io) server so Claude (or any MCP client) can connect over stdio and use the bridge as a tool.

> **Status:** alpha. Single-player / local testing only. See [Security](#security) before installing.

---

## Features

- **Lua REPL inside Minecraft.** Persistent environment — variables and helpers you define in one call stay around for the next one.
- **Mojang-mapped names everywhere.** Write `Minecraft:getInstance():getFps()`, not `eev.b().a()`. Works the same on every supported MC version.
- **Auto-downloaded mappings.** On first launch the mod fetches the official client mappings for your MC version from `launchermeta.mojang.com` and caches them under `~/.debugbridge/mappings/<version>.txt`. No manual setup, no version-specific bundles.
- **Reflection helpers.** `java.describe(obj)`, `java.methods(obj, "tick")`, `java.fields(obj)`, `java.supers(obj)`, and `java.find(pattern)` let you explore the API from inside the REPL without leaving the game.
- **Slash command execution.** Run any vanilla command from outside the game.
- **Structured snapshot endpoint.** One call returns a JSON blob of player state (position, health, food, dimension), world state (time, weather), and client FPS — no Lua required.
- **Optional MCP server.** A small Node.js bridge exposes the same operations as MCP tools so Claude Desktop, Claude Code, or any other MCP client can use the mod as a tool source.

## Supported versions

| Minecraft | Loader            | Status      |
| --------- | ----------------- | ----------- |
| 1.21.11   | Fabric Loader 0.16+ | Working   |
| 1.19      | Fabric Loader 0.14+ | Working   |
| 26.1      | Fabric Loader 0.16+ | In progress |

The core bridge is version-agnostic. Adding a new MC version is mostly a thin shim module against that version's mappings.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for your Minecraft version.
2. Drop `debugbridge-<mcversion>-<modversion>.jar` into your `mods/` folder.
3. Launch the game once. On first launch the mod will download mappings and start a WebSocket server on port `9876`. You should see lines like this in the log:
   ```
   [DebugBridge] Downloading 1.21.11 mappings from Mojang...
   [DebugBridge] Parsed 8451 classes from mappings.
   [DebugBridge] Server started on port 9876
   ```

The mod has **no fabric-api dependency** and runs **client-side only**.

## Configuration

Optional. Drop a `debugbridge.json` into your instance's `config/` folder:

```json
{
    "port": 9876,
    "timeout_ms": 5000,
    "max_results": 100,
    "lua": {
        "max_execution_time_ms": 5000
    }
}
```

If the file isn't there, defaults are used and a line is logged.

## Connecting

The mod listens on `ws://127.0.0.1:9876` (or whatever port you configured). It speaks a small JSON request/response protocol. Three ways to talk to it:

### 1. The MCP server (recommended for AI agents)

```bash
cd mcp-server
npm install
npm run build
node dist/index.js
```

Wire it into your MCP client of choice (Claude Desktop, Claude Code, etc.) as a stdio server. Available tools:

- `mc_connect` — connect to the running mod
- `mc_disconnect`
- `mc_execute` — run Lua, returns output and the value of the last expression
- `mc_search` — regex search over Mojang class/method/field names
- `mc_snapshot` — structured player + world state
- `mc_run_command` — run a vanilla slash command

### 2. Any WebSocket client

Send JSON messages of the form `{"type": "execute", "code": "..."}`, `{"type": "snapshot"}`, etc. See `mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java` for the wire format.

### 3. Direct Lua over the bridge

The Lua environment exposes a `java` global with these helpers:

| Function                       | What it does                                            |
| ------------------------------ | ------------------------------------------------------- |
| `java.import(className)`       | Look up a class by Mojang-mapped name                    |
| `java.new(class, args...)`     | Construct an instance                                    |
| `java.typeof(obj)`             | Get an object's class name                               |
| `java.cast(obj, className)`    | View an object as a different type                       |
| `java.iter(iterable)`          | Iterate over a Java collection                           |
| `java.array(collection)`       | Convert to a Lua table                                   |
| `java.isNull(obj)`             | Null check                                               |
| `java.ref(refId)`              | Retrieve a previously stored object reference            |
| `java.describe(obj)`           | Class + fields + methods + supers, all at once           |
| `java.methods(obj, [filter])`  | List methods, optionally filtered by name                |
| `java.fields(obj, [filter])`   | List fields                                              |
| `java.supers(obj)`             | Class hierarchy and interfaces                           |
| `java.find(pattern, [scope])`  | Regex search across loaded mappings                      |

Field access uses `.`, method calls use `:`. Use `return <expr>` to return a value to the caller.

### Lua snippets

Get the player's current position:

```lua
local mc = java.import("net.minecraft.client.Minecraft"):getInstance()
local p = mc.player
return { x = p:getX(), y = p:getY(), z = p:getZ() }
```

Find every class whose name contains "Entity" and is in `world`:

```lua
return java.find("world.*Entity", "class")
```

List the methods on `LocalPlayer` matching `tick`:

```lua
local p = java.import("net.minecraft.client.Minecraft"):getInstance().player
return java.methods(p, "tick")
```

Spawn 10 lightning bolts on the player (don't actually do this):

```lua
local mc = java.import("net.minecraft.client.Minecraft"):getInstance()
local LightningBolt = java.import("net.minecraft.world.entity.LightningBolt")
for i = 1, 10 do
    local bolt = java.new(LightningBolt, mc.level)
    bolt:setPos(mc.player:getX(), mc.player:getY(), mc.player:getZ())
    mc.level:addFreshEntity(bolt)
end
```

## Security

DebugBridge gives anyone who can connect to its port the ability to **execute arbitrary code in your Minecraft client**, with full reflective access to the JVM. Treat it like an unauthenticated remote debugger because that is essentially what it is.

- The server binds to `127.0.0.1` only.
- Do **not** run this mod on a public network or while port-forwarding `9876`.
- Do **not** run it on multiplayer servers — it's a client-side mod, but a malicious server can't trigger it; the danger is anything else on your machine.
- It is intended for single-player worlds, local test instances, and trusted dev environments.

## Building from source

Requirements: JDK 21 (Loom 1.9.2 needs 21+).

```bash
cd mod
JAVA_HOME=/path/to/jdk21 ./gradlew :fabric-1.21.11:build
```

The output jar lands at `mod/fabric-1.21.11/build/libs/debugbridge-1.21.11-1.0.0.jar`. The `build-and-deploy-1.21.11.sh` script at the repo root will build and copy the jar into a Modrinth profile in one shot — edit the `TARGET_DIR` variable to match your install.

The repo is laid out as a Gradle multi-project:

```
mod/
  core/                  — version-agnostic Lua bridge, mapping resolver, WebSocket server
  fabric-1.19/           — Fabric shim for Minecraft 1.19
  fabric-1.21.11/        — Fabric shim for Minecraft 1.21.11
  fabric-26.1/           — (work in progress)
mcp-server/              — Node.js MCP server that talks to the mod over WebSocket
```

Most code lives in `core/`. Each `fabric-*` module is a thin entrypoint that wires the core bridge into that version's lifecycle and provides a small `GameStateProvider` for the snapshot endpoint.

Run the core test suite with:

```bash
cd mod
./gradlew :core:test
```

## License

MIT — see [`LICENSE`](./LICENSE).

## Acknowledgements

- [LuaJ](https://github.com/luaj/luaj) — pure-Java Lua 5.2 implementation that powers the REPL.
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) — embedded WebSocket server.
- Mojang for publishing the official mappings.
- The Fabric project for everything else.
