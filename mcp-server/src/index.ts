#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { BridgeSession } from "./session.js";

const session = new BridgeSession();

const server = new McpServer({
    name: "minecraft-debug-bridge",
    version: "1.0.0",
});

// mc_connect
server.tool(
    "mc_connect",
    `Connect to a running Minecraft instance with the DebugBridge mod.
Must be called before any other mc_* tools. Returns version and mapping info.`,
    {
        port: z.number().optional().describe("WebSocket port the mod is listening on. Default: 9876"),
    },
    async ({ port }) => {
        if (session.isConnected) {
            return { content: [{ type: "text" as const, text: "Already connected. Use mc_disconnect first." }] };
        }
        try {
            const info = await session.connect(port ?? 9876);
            return { content: [{ type: "text" as const, text: JSON.stringify(info, null, 2) }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: `Connection failed: ${msg}` }], isError: true };
        }
    }
);

// mc_disconnect
server.tool(
    "mc_disconnect",
    "Disconnect from the Minecraft instance and clear all state.",
    {},
    async () => {
        session.disconnect();
        return { content: [{ type: "text" as const, text: "Disconnected." }] };
    }
);

// mc_execute
server.tool(
    "mc_execute",
    `Execute Lua code in the Minecraft session. The Lua environment is persistent — variables and functions defined in earlier calls remain available.

The "java" global table provides:
- java.import(className) — import a Minecraft class by Mojang name
- java.new(class, args...) — create an instance
- java.typeof(obj) — get the Mojang class name
- java.cast(obj, className) — view object as a different type
- java.iter(iterable) — iterate over Java collections
- java.array(collection) — convert to a Lua table
- java.isNull(obj) — null check
- java.ref(refId) — retrieve a stored object reference

Reflection helpers for exploring API:
- java.describe(obj) — full dump: class, fields, methods, supers
- java.methods(obj, [filter]) — list methods (optional name filter)
- java.fields(obj, [filter]) — list fields (optional name filter)
- java.supers(obj) — class hierarchy and interfaces
- java.find(pattern, [scope]) — search mappings for classes/methods/fields

Java objects support field access (obj.fieldName) and method calls (obj:methodName(args)).
All names use Mojang-mapped names, regardless of Minecraft version.
Use "return <value>" to get a value back. Use print() for debug output.`,
    {
        code: z.string().describe("Lua code to execute"),
    },
    async ({ code }) => {
        try {
            const resp = await session.send("execute", { code });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            let text = "";
            if (resp.output) text += resp.output + "\n";
            if (resp.result) text += JSON.stringify(resp.result, null, 2);
            return { content: [{ type: "text" as const, text: text.trim() || "(no output)" }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
);

// mc_search
server.tool(
    "mc_search",
    `Search for Minecraft classes, methods, or fields by regex pattern.
Searches over Mojang-mapped names. Useful for discovering API surface.
Returns up to 100 results.`,
    {
        pattern: z.string().describe("Regex pattern to search for (case-insensitive)"),
        scope: z.enum(["class", "method", "field", "all"]).optional()
            .describe("What to search. Default: 'all'"),
    },
    async ({ pattern, scope }) => {
        try {
            const resp = await session.send("search", { pattern, scope: scope ?? "all" });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            return { content: [{ type: "text" as const, text: JSON.stringify(resp.result, null, 2) }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
);

// mc_snapshot
server.tool(
    "mc_snapshot",
    `Get a structured snapshot of the current game state. Returns player
position, health, food, dimension, game mode, time of day, weather, etc.
No Lua needed — quick overview of current state.`,
    {},
    async () => {
        try {
            const resp = await session.send("snapshot", {});
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            return { content: [{ type: "text" as const, text: JSON.stringify(resp.result, null, 2) }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
);

// mc_run_command
server.tool(
    "mc_run_command",
    `Execute a Minecraft slash command (e.g., "/give @s minecraft:diamond 64").
The leading "/" is optional.`,
    {
        command: z.string().describe("The command to run"),
    },
    async ({ command }) => {
        try {
            const cmd = command.startsWith("/") ? command.substring(1) : command;
            const resp = await session.send("runCommand", { command: cmd });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            return { content: [{ type: "text" as const, text: JSON.stringify(resp.result, null, 2) }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
);

// Start stdio transport
const transport = new StdioServerTransport();
await server.connect(transport);
