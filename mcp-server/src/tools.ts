import { z } from "zod";
import { BridgeSession } from "./session.js";

const session = new BridgeSession();

export const tools = [
    {
        name: "mc_connect",
        description: `Connect to a running Minecraft instance with the DebugBridge mod.
Must be called before any other mc_* tools. Returns version and mapping info.`,
        inputSchema: {
            port: z.number().optional().describe(
                "WebSocket port the mod is listening on. Default: 9876"
            ),
        },
        handler: async ({ port }: { port?: number }) => {
            if (session.isConnected) {
                return { content: [{ type: "text" as const, text: "Already connected. Use mc_disconnect first." }] };
            }
            try {
                const info = await session.connect(port ?? 9876);
                return {
                    content: [{ type: "text" as const, text: JSON.stringify(info, null, 2) }]
                };
            } catch (e: unknown) {
                const msg = e instanceof Error ? e.message : String(e);
                return { content: [{ type: "text" as const, text: `Connection failed: ${msg}` }], isError: true };
            }
        },
    },

    {
        name: "mc_disconnect",
        description: "Disconnect from the Minecraft instance and clear all state.",
        inputSchema: {},
        handler: async () => {
            session.disconnect();
            return { content: [{ type: "text" as const, text: "Disconnected." }] };
        },
    },

    {
        name: "mc_execute",
        description: `Execute Lua code in the Minecraft session. The Lua environment is
persistent — variables and functions defined in earlier calls remain available.

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
        inputSchema: {
            code: z.string().describe("Lua code to execute"),
        },
        handler: async ({ code }: { code: string }) => {
            const resp = await session.send("execute", { code });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            let text = "";
            if (resp.output) text += resp.output + "\n";
            if (resp.result) text += JSON.stringify(resp.result, null, 2);
            return { content: [{ type: "text" as const, text: text.trim() || "(no output)" }] };
        },
    },

    {
        name: "mc_search",
        description: `Search for Minecraft classes, methods, or fields by regex pattern.
Searches over Mojang-mapped names. Useful for discovering API surface.
Returns up to 100 results.`,
        inputSchema: {
            pattern: z.string().describe("Regex pattern to search for (case-insensitive)"),
            scope: z.enum(["class", "method", "field", "all"]).optional()
                .describe("What to search. Default: 'all'"),
        },
        handler: async ({ pattern, scope }: { pattern: string; scope?: string }) => {
            const resp = await session.send("search", { pattern, scope: scope ?? "all" });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            return { content: [{ type: "text" as const, text: JSON.stringify(resp.result, null, 2) }] };
        },
    },

    {
        name: "mc_snapshot",
        description: `Get a structured snapshot of the current game state. Returns player
position, health, food, dimension, game mode, time of day, weather, etc.
No Lua needed — quick overview of current state.`,
        inputSchema: {},
        handler: async () => {
            const resp = await session.send("snapshot", {});
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            return { content: [{ type: "text" as const, text: JSON.stringify(resp.result, null, 2) }] };
        },
    },

    {
        name: "mc_run_command",
        description: `Execute a Minecraft slash command (e.g., "/give @s minecraft:diamond 64").
The leading "/" is optional.`,
        inputSchema: {
            command: z.string().describe("The command to run"),
        },
        handler: async ({ command }: { command: string }) => {
            const cmd = command.startsWith("/") ? command.substring(1) : command;
            const resp = await session.send("runCommand", { command: cmd });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            return { content: [{ type: "text" as const, text: JSON.stringify(resp.result, null, 2) }] };
        },
    },
];
