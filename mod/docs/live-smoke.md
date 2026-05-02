# DebugBridge MCP Live Smoke

This is the live verification path for logger and Mixin compatibility. Use MCP
tool calls for Minecraft actions; do not route those actions through shell-based
Minecraft control wrappers.

## Prepare

From the DebugBridge root:

```powershell
.\tools\debugbridge-live-smoke.ps1
```

This builds the affected DebugBridge artifacts and copies the 26.2 Fabric jar to
the render mod's `run/mods` directory.

To start the render mod client from the script:

```powershell
.\tools\debugbridge-live-smoke.ps1 -StartClient
```

## MCP Sequence

1. Connect to the running client with `mc_connect`.
2. Install a throttled logger with `mc_logger`.

   Suggested first target:

   ```text
   net.minecraft.client.Minecraft.tick
   ```

   Use a short duration and a throttle filter, for example `interval_ms = 200`.

3. Open or enter a world with `mc_execute`.

   The known-good Lua shape is:

   ```lua
   local Minecraft = java.import('net.minecraft.client.Minecraft')
   local Thread = java.import('java.lang.Thread')
   local mc = Minecraft.getInstance()
   mc.options.pauseOnLostFocus = false
   local flows = mc:createWorldOpenFlows()
   flows:openWorld('New World', java.new(Thread))
   return 'opening world from ' .. Thread:currentThread():getName()
   ```

4. Assert the logger output file exists and is non-empty.

   The script can check a known file path after MCP logging:

   ```powershell
   .\tools\debugbridge-live-smoke.ps1 -SkipBuild -SmokeOutputPath C:\Users\ttski\AppData\Local\Temp\debugbridge-tick.log
   ```

## Render Logger Target

After `Minecraft.tick` passes, test the render-side path with a throttled logger:

```text
temotskipa.minecraftvulkanimprovementmod.client.vulkan.VulkanModernPipelineState.writeTerrainPushConstants
```

Do not run this target unthrottled. It is hot and can produce very large files.
