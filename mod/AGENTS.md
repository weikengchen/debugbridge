# DebugBridge Agent Map

This file is the short entry point for agents. Keep details in linked files and
keep enforceable rules in tests or scripts.

## Start Here

- Architecture and module boundaries: `ARCHITECTURE.md`
- MCP live smoke path: `docs/live-smoke.md`
- Live smoke preparation script: `tools/debugbridge-live-smoke.ps1`
- Main verification command:
  `.\gradlew.bat :agent:test :core:test :hooks:jar :fabric-26.2-snapshot-4:jar --console=plain`

## Project Shape

- `core`: protocol, server, Lua bridge, mapping, snapshots, screenshots, textures,
  and logger service interfaces. It must stay independent of agent, hooks, and
  Fabric implementation packages.
- `hooks`: bootstrap-classloader runtime used by injected advice. It must stay
  small and must not depend on core, agent, or Fabric modules.
- `agent`: Java agent, Byte Buddy instrumentation, Mixin-safe bytecode handling,
  and direct logger service implementation.
- `fabric-*`: Minecraft-version-specific adapters. Version APIs stay inside their
  own Fabric module.

## Working Notes

- Use PowerShell-native search (`Get-ChildItem`, `Select-String`) if `rg` fails
  with `Access is denied` in Codex Desktop.
- Do not deduplicate agent and hooks advice classes without rechecking the
  classloader boundary in `ARCHITECTURE.md`.
- Prefer MCP tool calls for Minecraft live checks: `mc_connect`, `mc_logger`,
  and `mc_execute`.
- Treat `fabric-26.2-snapshot-4/src/main/resources/fabric.mod.json` as
  potentially user-edited unless your task explicitly touches metadata.
