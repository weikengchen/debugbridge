#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { BridgeSession } from "./session.js";
import { SessionInfo } from "./types.js";

const DEFAULT_PORT = (() => {
    const raw = process.env.DEBUGBRIDGE_PORT;
    if (!raw) return 9876;
    const n = Number(raw);
    return Number.isFinite(n) && n > 0 && n <= 65535 ? n : 9876;
})();

const session = new BridgeSession(DEFAULT_PORT);

const server = new McpServer({
    name: "minecraft-debug-bridge",
    version: "1.0.0",
});

/** Format session info for display */
function formatSessionInfo(info: Partial<SessionInfo>, port: number | null): string {
    const lines: string[] = [];
    if (info.version) lines.push(`Minecraft ${info.version}`);
    if (port) lines.push(`Port: ${port}`);
    if (info.gameDir) lines.push(`Game dir: ${info.gameDir}`);
    if (info.latestLog) lines.push(`Log: ${info.latestLog}`);
    if (info.mappingStatus) lines.push(`Mappings: ${info.mappingStatus}`);
    return lines.join("\n");
}

// mc_connect
server.tool(
    "mc_connect",
    `Connect to a running Minecraft instance with the DebugBridge mod.
Optional — other mc_* tools auto-connect if needed. Useful to specify a
non-default port or to get session info (version, mapping info, paths to
game directory and log files). Use the Read tool on latestLog / debugLog
to view game logs.

If port is not specified, will scan ports ${DEFAULT_PORT}-${DEFAULT_PORT + 9} to find the mod.
The port and game instance are remembered for auto-reconnect on subsequent calls.`,
    {
        port: z.number().optional().describe(`WebSocket port. Default: scan ${DEFAULT_PORT}-${DEFAULT_PORT + 9}`),
    },
    async ({ port }) => {
        if (session.isConnected) {
            const info = session.getSessionInfo();
            const connectedPort = session.getConnectedPort();
            return {
                content: [{
                    type: "text" as const,
                    text: `Already connected.\n${formatSessionInfo(info ?? {}, connectedPort)}\n\nUse mc_disconnect first to reconnect.`
                }]
            };
        }
        try {
            const info = await session.connect(port);
            const connectedPort = session.getConnectedPort();
            return {
                content: [{
                    type: "text" as const,
                    text: `Connected!\n${formatSessionInfo(info, connectedPort)}`
                }]
            };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: `Connection failed: ${msg}` }], isError: true };
        }
    }
);

// mc_disconnect
server.tool(
    "mc_disconnect",
    `Disconnect from the Minecraft instance. By default, keeps the port and
game instance remembered for auto-reconnect. Use reset=true to fully clear
all state (useful when switching to a different Minecraft instance).`,
    {
        reset: z.boolean().optional().describe("If true, also clear remembered port and instance info"),
    },
    async ({ reset }) => {
        if (reset) {
            session.reset();
            return { content: [{ type: "text" as const, text: "Disconnected and reset all session state." }] };
        }
        session.disconnect();
        return { content: [{ type: "text" as const, text: "Disconnected. Will auto-reconnect on next command." }] };
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

// mc_screenshot
server.tool(
    "mc_screenshot",
    `Capture the current Minecraft client framebuffer as a JPEG file on the
machine running the mod, and return its absolute path. Use the Read tool
to view the image.

The capture runs on the render thread and pauses for at most one frame;
the game otherwise continues. Works while the game is paused (returns the
last rendered frame).

Defaults are tuned for low-bandwidth visual inspection: downscale=2,
quality=0.75. Override only if you specifically need higher fidelity.

The mod and the MCP server must run on the same machine for the returned
path to be readable here.`,
    {
        downscale: z.number().int().min(1).max(16).optional()
            .describe("Integer downscale factor. 1 = full window resolution. 2 = half each axis (default)."),
        quality: z.number().min(0.05).max(1.0).optional()
            .describe("JPEG quality in [0.05, 1.0]. Default: 0.75."),
    },
    async ({ downscale, quality }) => {
        try {
            const payload: Record<string, unknown> = {};
            if (downscale !== undefined) payload.downscale = downscale;
            if (quality !== undefined) payload.quality = quality;
            const resp = await session.send("screenshot", payload);
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            const result = resp.result as
                | { path: string; width: number; height: number; sizeBytes: number; mimeType: string }
                | undefined;
            if (!result || typeof result.path !== "string") {
                return { content: [{ type: "text" as const, text: "Screenshot returned no path." }], isError: true };
            }
            const kb = (result.sizeBytes / 1024).toFixed(1);
            return {
                content: [
                    {
                        type: "text" as const,
                        text: `${result.path}\n(${result.width}×${result.height} JPEG, ${kb} KB)`,
                    },
                ],
            };
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

// mc_inject_logger
server.tool(
    "mc_inject_logger",
    `Inject a runtime logger into a Minecraft method to trace calls.

Uses Java Agent instrumentation to inline logging hooks into the target method.
The logger captures method entry/exit, arguments, return values, and timing.

IMPORTANT: This modifies bytecode at runtime. While designed to be safe and
coexist with Mixin, avoid targeting methods in hot paths (called >10k/sec)
or methods critical to game stability unless necessary.

The output_file will be created on the machine running Minecraft.
Use the Read tool to view the log file.

Filter types:
- throttle: { "type": "throttle", "interval_ms": 200 } — rate limit logging
- arg_contains: { "type": "arg_contains", "index": 0, "substring": "Player" }
- arg_instanceof: { "type": "arg_instanceof", "index": 0, "class_name": "Entity" }
- sample: { "type": "sample", "n": 10 } — log every Nth call`,
    {
        method: z.string().describe(
            "Fully qualified method name using Mojang names, e.g. 'net.minecraft.client.Minecraft.tick'"
        ),
        duration_seconds: z.number().min(1).max(3600).optional()
            .describe("How long the logger stays active. Default: 60 seconds. Max: 1 hour."),
        output_file: z.string().optional()
            .describe("Path to log file. Default: /tmp/debugbridge-<method>-<timestamp>.log"),
        log_args: z.boolean().optional()
            .describe("Log method arguments. Default: true"),
        log_return: z.boolean().optional()
            .describe("Log return value. Default: false"),
        log_timing: z.boolean().optional()
            .describe("Log elapsed time. Default: true"),
        arg_depth: z.number().int().min(0).max(3).optional()
            .describe("Depth of argument inspection. 0=class@hash, 1+=toString(). Default: 1"),
        filter: z.object({
            type: z.enum(["throttle", "arg_contains", "arg_instanceof", "sample"]),
            interval_ms: z.number().optional(),
            index: z.number().int().optional(),
            substring: z.string().optional(),
            class_name: z.string().optional(),
            n: z.number().int().optional(),
        }).optional().describe("Optional filter to reduce log volume"),
    },
    async ({ method, duration_seconds, output_file, log_args, log_return, log_timing, arg_depth, filter }) => {
        try {
            const resp = await session.send("injectLogger", {
                method,
                duration_seconds: duration_seconds ?? 60,
                output_file: output_file ?? null,
                log_args: log_args ?? true,
                log_return: log_return ?? false,
                log_timing: log_timing ?? true,
                arg_depth: arg_depth ?? 1,
                filter: filter ?? null,
            });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            const result = resp.result as { logger_id: number; output_file: string; message?: string };
            let text = `Logger #${result.logger_id} installed on ${method}\n`;
            text += `Output: ${result.output_file}\n`;
            text += `Duration: ${duration_seconds ?? 60} seconds`;
            if (result.message) text += `\n${result.message}`;
            return { content: [{ type: "text" as const, text }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
);

// mc_cancel_logger
server.tool(
    "mc_cancel_logger",
    `Cancel an active logger by ID. The logger stops immediately but the
injected advice bytecode remains (negligible overhead when inactive).`,
    {
        id: z.number().int().describe("Logger ID returned by mc_inject_logger"),
    },
    async ({ id }) => {
        try {
            const resp = await session.send("cancelLogger", { id });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            const result = resp.result as { cancelled: boolean };
            if (result.cancelled) {
                return { content: [{ type: "text" as const, text: `Logger #${id} cancelled.` }] };
            } else {
                return { content: [{ type: "text" as const, text: `Logger #${id} not found (may have already expired).` }] };
            }
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
);

// mc_list_loggers
server.tool(
    "mc_list_loggers",
    `List all currently active loggers. Shows logger ID, target method,
remaining time, and whether a filter is active.`,
    {},
    async () => {
        try {
            const resp = await session.send("listLoggers", {});
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            const result = resp.result as {
                loggers: Array<{ id: number; method: string; remaining_ms: number; has_filter: boolean }>;
                injected_methods: string[];
            };
            if (result.loggers.length === 0) {
                let text = "No active loggers.";
                if (result.injected_methods.length > 0) {
                    text += `\n\nMethods with advice installed (inactive): ${result.injected_methods.length}`;
                }
                return { content: [{ type: "text" as const, text }] };
            }
            let text = "Active loggers:\n";
            for (const logger of result.loggers) {
                const remainingSec = Math.round(logger.remaining_ms / 1000);
                text += `  #${logger.id}: ${logger.method} (${remainingSec}s remaining`;
                if (logger.has_filter) text += ", filtered";
                text += ")\n";
            }
            if (result.injected_methods.length > result.loggers.length) {
                text += `\nMethods with advice installed: ${result.injected_methods.length}`;
            }
            return { content: [{ type: "text" as const, text: text.trim() }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
);

// Start stdio transport
const transport = new StdioServerTransport();
await server.connect(transport);
