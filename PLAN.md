# DebugBridge improvement plan

Pre-publication hardening + API improvements, derived from analyzing ~2,000 `mc_*` agent tool calls captured in Claude Code session transcripts (`~/.claude/projects/*/*.jsonl`) across 40 sessions.

**We will not tag a public release until the *Pre-publication hygiene* and *Tier 1* items are done.**

## Top-line evidence

| Tool | Calls | Errors | Implication |
|---|---|---|---|
| `mc_search` | 779 | low | Workhorse — no change needed |
| `mc_get_class` | 428 | 14 token-cap hits | Add `view` param (#8) |
| `mc_get_method` | 358 | low | Healthy |
| `mc_execute` | 324 | ~30% | Most errors point at missing native endpoints (#4) or JPMS iteration (#7) |
| `mc_screenshot` | 25 | rare | Healthy |
| `mc_snapshot` | 3 | 0 | Underused — agents don't know it exists (#5) |
| `mc_logger` | **2** | **2 (100%)** | **Remove (#1)** |
| `mc_run_command` | 2 | 2 | **Hide unless enabled (#2)** |

Two things to keep in mind while reading the rest:

- **Static tools dominate.** ~1640 of ~2000 calls (82%) are mappings/source lookups. Runtime tools matter, but the bridge is on the smaller side of the call mix.
- **`mc_execute` is doing too much.** It's used for things a typed endpoint should serve (player state, slot walks, chat history). Most of Tier 1 is about reclaiming `mc_execute` for what it's actually good at: agent-driven exploration of the Java API.

## Pre-publication hygiene (blockers for the next public tag)

### 1. ~~Hide / remove `injectLogger` family from the public release~~
- ~~Evidence: `mc_logger` was called 2 times across 40 sessions, both 100% errored.~~
- **Done 2026-04-28** via option (a) gating: `BridgeConfig.loggerInjectionEnabled` (default false) → `BridgeServer.setLoggerInjectionEnabled` → injectLogger/cancelLogger/listLoggers cases return `Unknown request type` when off. mcdev-mcp side: `mc_logger` only registered when `MCDEV_LOGGER_INJECTION=1`. Both sides default off.
- Re-enable (dev only): set `logger_injection_enabled: true` in `~/.minecraft/config/debugbridge.json` AND `MCDEV_LOGGER_INJECTION=1` for the mcdev-mcp process.
- [x] Done

### 2. ~~Hide `runCommand` unless explicitly enabled~~
- **Done 2026-04-28**, same shape as #1. Bridge: `BridgeConfig.runCommandEnabled` (default false) → `BridgeServer.setRunCommandEnabled` → returns `Unknown request type` when off. mcdev-mcp: `mc_run_command` only registered when `MCDEV_RUN_COMMAND=1`. Web UI's stale `bridge.runCommand` method has zero call-sites — gating off is non-breaking.
- [x] Done

### 3. ~~Fix `mc_find_refs` `Cannot find module 'bindings'` crash (in mcdev-mcp)~~
- ~~Surfaces for any user who installs the public mcdev-mcp release. better-sqlite3 native binding issue.~~
- **Resolved 2026-04-16** by migrating from `better-sqlite3` (native) → `node:sqlite` → `sql.js` (pure JS + WASM). Last occurrence: 2026-04-16 05:07 UTC; 0 errors across 50+ subsequent `mc_find_refs` calls. Migration commits in `~/if-local/mcdev-mcp/`: `31185a3`, `d6babbc`, `62e4ca5`.
- [x] Done

## Tier 1 — high-leverage agent UX

### 4. ~~Wrap the existing native runtime endpoints as MCP tools~~  ← was the highest leverage item
- **Done 2026-04-28.** All 11 wrappers added in `~/if-local/mcdev-mcp/src/tools/runtime/`: `nearby-entities.ts`, `entity-details.ts`, `nearby-blocks.ts`, `block-details.ts`, `looked-at-entity.ts`, `set-entity-glow.ts`, `set-block-glow.ts`, `clear-block-glow.ts`, `get-item-texture.ts`, `get-entity-item-texture.ts`, `get-item-texture-by-id.ts`. All registered in `runtime/index.ts`. Default tool count went 7 → 15 (counting the dev-gated removals from #1, #2).
- Open follow-ups now visible: agents will start using these instead of `mc_execute`, which means the `mc_execute` Lua-iteration timeouts should drop. Re-run the analysis in a few weeks to see if any new patterns emerge that suggest more wrappers (per #6 — chat history, screen inspect).
- [x] Done

### 5. Enrich `snapshot` payload + advertise it in `mc_execute` description
- ~53/324 `mc_execute` calls (16%) are pure player-state lookups (`x, y, z, dim, yaw`). `mc_snapshot` was called only 3 times — agents don't realize it serves this.
- Bridge change ([`BridgeServer.java`](mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java) `handleSnapshot` + provider impls): add yaw, pitch, deltaMovement, vehicle, look-vector, selected hotbar slot, raycast target (use existing `mc.hitResult`).
- mcdev-mcp change: update `mc_execute` description in `~/if-local/mcdev-mcp/src/tools/runtime/execute.ts` to point at `mc_snapshot` for state lookups. Steers the model away from the redundant pattern.
- Effort: S.
- [ ] Done

### 6. Add `chatHistory` and `screenInspect` endpoints to the bridge
- 49 `mc_execute` calls walk `gui:getChat().allMessages` by hand.
- 25 walk `screen.getMenu().slots` by hand. Several timed out doing per-slot data-component extraction in Lua.
- Native endpoints (one provider impl per Fabric module, same pattern as `NearbyBlocksProvider`) avoid both the bridge overhead and the repeated reflection cost.
- Effort: M.
- [ ] Done

## Tier 2 — friction reduction

### 7. Make `java.iter()` use interface dispatch before reflection
- 12 `mc_execute` failures trying to iterate JPMS-private collections (e.g. `Unable to make HashMap$KeySet.iterator() accessible: module java.base does not "opens java.util" to unnamed module`).
- Going through public `Iterable`/`Collection` interfaces sidesteps JPMS entirely. Should also document this in the `mc_execute` tool description so the model knows to wrap reflected fields in `java.iter`.
- File: `mod/core/src/main/java/com/debugbridge/core/lua/` — find the `java.iter` implementation.
- Effort: M.
- [ ] Done

### 8. Add `view` parameter to `mc_get_class`
- 14 calls (3.3%) hit the MCP token cap, mostly on the same heavily-inspected classes (e.g. `ClientPacketListener` at 138K chars). When the harness saves them to a sidecar file, the agent often gives up rather than reading it.
- Add `view: "summary" | "methods" | "fields" | "full"`, default `"summary"`. `summary` returns one line per method/field, signatures only, no bodies.
- File: `~/if-local/mcdev-mcp/src/tools/static/get-class.ts`.
- Effort: S.
- [ ] Done

### 9. Structured connect-failure response
- `mc_connect` failures return `Last error: ECONNREFUSED 127.0.0.1:9885` — useful to a human, opaque to an agent. Three sessions had repeated retries against the wrong port range.
- Return `{action: "start_minecraft", ports_tried: [9876, 9877, ...], message: "DebugBridge mod is not running. Start Minecraft with the mod loaded."}` so the agent can decide whether to retry vs. ask the user.
- File: `~/if-local/mcdev-mcp/src/tools/runtime/connect.ts`.
- Effort: S.
- [ ] Done

## Tier 3 — for the next data-gathering cycle

### 10. Add lightweight per-request logging to `BridgeServer`
- **Partial progress 2026-04-28 (in mcdev-mcp, not luabridge):** new `ScriptLogger` writes `mc_execute` calls to `~/Library/Application Support/mcdev-mcp/script-logs/all.jsonl` + `errors.jsonl`, gated on `MCDEV_SCRIPT_LOGS=1` (or the Claude Desktop `enable_script_logging` toggle). 10MB rotation, 2 keep. New `mc_script_logs` tool exposes the file to agents. Files: `~/if-local/mcdev-mcp/src/tools/runtime/script-logger.ts` and `script-logs.ts`.
- **Still open (the original ask):** server-side per-request logging in [`BridgeServer.onMessage`](mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java) covering **every** WebSocket request type. Today the script-logger only sees `mc_execute` (~16% of agent calls); the other 1700+ calls (`mc_search`/`mc_get_class`/etc.) are static-side and don't hit the bridge at all, but the runtime endpoints `mc_snapshot`/`mc_screenshot`/`mc_connect`/`mc_run_command`/`mc_logger` plus all the new entity/block endpoints are still invisible. Other agent clients (Cursor, Cline, Web UI, raw scripts) are also invisible.
- Add `BridgeConfig.requestLog: String?` (default null = off). When set, [`BridgeServer.onMessage`](mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java) writes one JSON line per request: `{ts, type, payload_keys, payload_size_bytes, duration_ms, success, error_class}`.
- **Don't log full payloads** (Lua source can be large; PII risk for command text) — log shape + outcome only. The mcdev-mcp script-logger currently logs full Lua code, which is fine for a dev-side opt-in but the wrong default for the bridge.
- Rotate or cap at a configured size.
- Effort: S.
- [ ] Done

## Surprises from the data (background, no action items)

- `mc_search` is by far the most-called tool (779) but agents almost never filter by `type` (1/779). The "search → get_class" pipeline is dominant — agents grep names broadly, then drill.
- Connect/execute ratio is healthy (~8 executes per connect). Agents keep sessions alive and use `_G` for cross-call state. The maintainer probably worried about reconnect storms; doesn't happen in practice.
- Nobody uses `mc_run_command`. Agents prefer Lua because it returns values they can branch on.
- Long `mc_execute` chains in luabridge's own session (33-call chain in transcript `86390bec-…`) were exploration of the item-render pipeline. That kind of exploration is exactly what `mc_execute` *should* support — just not for things a native endpoint already covers (#4–6).

## How to use this doc

1. Pick an item, work it, check the box.
2. If the item turns out to need a different approach than described, **edit the item before doing the work** — don't let the doc drift behind the code.
3. Once shipped, leave the box checked. We can compress to a `Done` section later if it gets unwieldy.
4. New evidence (more transcripts, real user reports) → add a new item or amend the relevant one. The numbered IDs are for reference in commit messages, not a fixed order.
