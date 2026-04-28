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

### 1. Hide / remove `injectLogger` family from the public release
- Evidence: `mc_logger` was called 2 times across 40 sessions, both 100% errored (`-javaagent:` flag was never set, so `LoggerService.UNAVAILABLE` returned).
- Two options:
  - **(a) Gate** the three handlers in [`BridgeServer.java:241-243`](mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java) behind a `BridgeConfig.loggerInjectionEnabled` flag (default false). Cheap.
  - **(b) Build flavor split**: keep the agent + hooks modules out of the public jar entirely, dev-only.
- Files involved: [`mod/core/src/main/java/com/debugbridge/core/logging/`](mod/core/src/main/java/com/debugbridge/core/logging/), [`mod/agent/`](mod/agent/), [`mod/hooks/`](mod/hooks/).
- Effort: S (a) / M (b).
- [ ] Done

### 2. Hide `runCommand` unless explicitly enabled
- 2 calls, both errored. Same shape as #1.
- Add `BridgeConfig.runCommandEnabled` (default false). Skip the `case "runCommand"` dispatch when off so the wrapper can return a clean "feature disabled" rather than a runtime crash.
- Effort: S.
- [ ] Done

### 3. Fix `mc_find_refs` `Cannot find module 'bindings'` crash (in mcdev-mcp)
- Surfaces for any user who installs the public mcdev-mcp release. better-sqlite3 native binding issue.
- Lives in `~/if-local/mcdev-mcp/`, not this repo — tracked here for visibility.
- Effort: S.
- [ ] Done

## Tier 1 — high-leverage agent UX

### 4. Wrap the existing native runtime endpoints as MCP tools  ← **highest leverage**
- The bridge already implements `nearbyEntities`, `entityDetails`, `nearbyBlocks`, `blockDetails`, `lookedAtEntity`, `setEntityGlow`, `setBlockGlow`, `clearBlockGlow`, `getItemTexture`, `getEntityItemTexture`, `getItemTextureById` — **none are exposed in `mcdev-mcp/src/tools/runtime/index.ts`.**
- Result: agents reach for `mc_execute` and re-implement the same loops in Lua. ~13 of 324 `mc_execute` calls timed out doing exactly the per-call bridge-cost iteration that `CLAUDE.md` warns against.
- Action: add `mc_nearby_entities`, `mc_entity_details`, `mc_nearby_blocks`, `mc_block_details`, `mc_looked_at_entity`, `mc_set_entity_glow`, `mc_set_block_glow`, `mc_clear_block_glow`, `mc_get_item_texture` — TypeScript glue modeled on `snapshot.ts`.
- Effort: S.
- [ ] Done

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
- We currently only have agent-usage data because Claude Code keeps transcripts. Any other client (Cursor, Cline, custom agents, future Claude Code formats) gives us nothing.
- Add `BridgeConfig.requestLog: String?` (default null = off). When set, [`BridgeServer.onMessage`](mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java) writes one JSON line per request: `{ts, type, payload_keys, duration_ms, success, error_class}`.
- Don't log full payloads (Lua source can be large) — log shape + outcome. Add `payload_size_bytes` if useful.
- Rotate or cap at a configured size to avoid runaway disk use.
- This is what we *want* the "log feature" to be for, replacing the dead bytecode injector as the data-gathering tool.
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
