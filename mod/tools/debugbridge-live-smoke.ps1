param(
    [string] $DebugBridgeRoot = (Resolve-Path "$PSScriptRoot\..").Path,
    [string] $RenderModRoot = "C:\Users\ttski\Projects\IdeaProjects\minecraft-vulkan-improvement-mod-26.2-dev",
    [switch] $SkipBuild,
    [switch] $StartClient,
    [string] $SmokeOutputPath
)

$ErrorActionPreference = "Stop"

function Write-Step([string] $Message) {
    Write-Host "[debugbridge-live-smoke] $Message"
}

$gradle = Join-Path $DebugBridgeRoot "gradlew.bat"
$renderGradle = Join-Path $RenderModRoot "gradlew.bat"
$agentJar = Join-Path $DebugBridgeRoot "agent\build\libs\debugbridge-agent-1.1.0.jar"
$hooksJar = Join-Path $DebugBridgeRoot "hooks\build\libs\debugbridge-hooks-1.1.0.jar"
$fabricJarGlob = Join-Path $DebugBridgeRoot "fabric-26.2-dev\build\libs\debugbridge-26.2-dev-*.jar"
$renderMods = Join-Path $RenderModRoot "run\mods"

if (-not $SkipBuild) {
    Write-Step "Building DebugBridge artifacts"
    # Primary verification tasks: :agent:test :core:test :hooks:jar :fabric-26.2-dev:jar
    $gradleTasks = @(":agent:test", ":core:test", ":hooks:jar", ":fabric-26.2-dev:jar")
    & $gradle @gradleTasks "--console=plain"
    if ($LASTEXITCODE -ne 0) {
        throw "DebugBridge Gradle verification failed with exit code $LASTEXITCODE"
    }
}

$fabricJar = Get-ChildItem -Path $fabricJarGlob |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $fabricJar) {
    throw "Could not find built debugbridge-26.2-dev jar at $fabricJarGlob"
}

New-Item -ItemType Directory -Force -Path $renderMods | Out-Null
Get-ChildItem -Path (Join-Path $renderMods "debugbridge-26.2-*.jar") -ErrorAction SilentlyContinue |
    Remove-Item -Force

$destination = Join-Path $renderMods $fabricJar.Name
Copy-Item -Path $fabricJar.FullName -Destination $destination -Force
Write-Step "Copied $($fabricJar.Name) to $destination"

if ($StartClient) {
    if (-not (Test-Path $renderGradle)) {
        throw "Render mod Gradle wrapper not found: $renderGradle"
    }
    if (-not (Test-Path $agentJar)) {
        throw "DebugBridge agent jar not found: $agentJar"
    }
    if (-not (Test-Path $hooksJar)) {
        throw "DebugBridge hooks jar not found: $hooksJar"
    }

    Write-Step "Starting Minecraft client from render mod workspace"
    Push-Location $RenderModRoot
    try {
        & $renderGradle `
            "-Pdebugbridge.agent=true" `
            "-Pdebugbridge.agent.jar=$agentJar" `
            "-Pdebugbridge.hooks.jar=$hooksJar" `
            "runClient" `
            "--console=plain"
    } finally {
        Pop-Location
    }
}

if ($SmokeOutputPath) {
    if (-not (Test-Path $SmokeOutputPath)) {
        throw "Smoke output file does not exist: $SmokeOutputPath"
    }
    $length = (Get-Item $SmokeOutputPath).Length
    if ($length -le 0) {
        throw "Smoke output file is empty: $SmokeOutputPath"
    }
    Write-Step "Smoke output file is non-empty: $SmokeOutputPath ($length bytes)"
}

Write-Step "Next MCP calls: mc_connect, mc_logger, mc_execute. See docs/live-smoke.md."
