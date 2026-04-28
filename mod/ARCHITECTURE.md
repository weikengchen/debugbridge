# DebugBridge Architecture

DebugBridge is split by runtime boundary, not by feature alone. The key rule is
that code crossing classloaders or Minecraft versions must do so through a small,
named interface.

## Module Boundaries

Allowed production dependency directions:

- `core`: no DebugBridge module dependencies.
- `hooks`: bootstrap runtime only. It may use JDK APIs and compile-only Byte
  Buddy annotations where needed.
- `agent`: may depend on `core` contracts and `hooks` bootstrap APIs. It must not
  depend on Fabric modules.
- `fabric-*`: may depend on `core` and on Minecraft/Fabric APIs for that module's
  target version. It must not depend on `agent` or `hooks`.

Disallowed edges that are enforced by `RepositoryScaffoldingTest`:

- `core -> hooks`
- `core -> agent`
- `core -> fabric-*`
- `hooks -> core`
- `hooks -> agent`
- `hooks -> fabric-*`
- `agent -> fabric-*`
- `fabric-* -> agent`
- `fabric-* -> hooks`

## Classloader Rules

- The hooks jar is loaded on the bootstrap classloader.
- The agent jar must not embed hooks classes.
- `agent jar must not embed hooks` is enforced by `AgentPackagingTest`.
- `AgentLoggingAdvice` is agent-owned because Byte Buddy reads it from the agent
  classloader while creating transformed bytecode.
- `com.debugbridge.hooks.LoggingAdvice` remains bootstrap-owned documentation and
  compatibility surface. Do not merge it with the agent-owned advice class unless
  a test proves the transformed bytecode still resolves hook calls correctly.

## Mixin-Safe Injection

- `DebugBridgeAgent` owns lifecycle only.
- `AdviceInjector` owns transformation policy.
- `BytecodeObserver` captures post-Mixin bytecode for later redefine.
- Loaded classes in a Mixin environment must use cached post-Mixin bytecode or
  fail safely instead of falling back to standard retransformation.

## Logger Contract

- `LoggerService` is the core boundary used by bridge handlers.
- `ReflectiveLoggerService` is the Fabric-facing service that binds to agent and
  hooks classes reflectively.
- `LoggerServiceImpl` is the direct agent implementation.
- Shared behavior, such as generated output-file paths, belongs in `core` helper
  classes so the reflective and direct paths cannot drift.

## Verification

Primary command:

```powershell
.\gradlew.bat :agent:test :core:test :hooks:jar :fabric-26.2-snapshot-4:jar --console=plain
```

Live Minecraft verification starts with `docs/live-smoke.md`.
