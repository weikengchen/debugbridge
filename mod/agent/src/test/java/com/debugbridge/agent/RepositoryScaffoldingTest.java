package com.debugbridge.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryScaffoldingTest {
    private static void requireNoImports(String module, List<String> forbiddenImports, List<String> violations)
            throws IOException {
        Path sourceRoot = repoRoot().resolve(module).resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                for (String forbiddenImport : forbiddenImports) {
                    if (source.contains(forbiddenImport)) {
                        violations.add(repoRoot().relativize(path) + " imports " + forbiddenImport);
                    }
                }
            }
        }
    }
    
    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).getParent();
    }
    
    @Test
    void rootAgentMapPointsToArchitectureAndVerification() throws IOException {
        Path agents = repoRoot().resolve("AGENTS.md");
        Path architecture = repoRoot().resolve("ARCHITECTURE.md");
        
        assertTrue(Files.isRegularFile(agents), "AGENTS.md should be the short repo map for future agents");
        assertTrue(Files.isRegularFile(architecture), "ARCHITECTURE.md should be the source of module boundaries");
        
        String agentMap = Files.readString(agents);
        assertTrue(agentMap.contains("ARCHITECTURE.md"), "AGENTS.md should point to the architecture source of truth");
        assertTrue(agentMap.contains(":agent:test :core:test :hooks:jar :fabric-26.2-dev:jar"),
                "AGENTS.md should contain the primary DebugBridge verification command");
        assertTrue(agentMap.contains("MCP live smoke"), "AGENTS.md should tell agents where the live smoke path starts");
        
        String architectureMap = Files.readString(architecture);
        assertTrue(architectureMap.contains("core -> hooks"), "ARCHITECTURE.md should document disallowed core-to-hooks edges");
        assertTrue(architectureMap.contains("hooks -> core"), "ARCHITECTURE.md should document disallowed hooks-to-core edges");
        assertTrue(architectureMap.contains("agent jar must not embed hooks"),
                "ARCHITECTURE.md should document the bootstrap hooks packaging invariant");
    }
    
    @Test
    void productionModulesKeepDeclaredDependencyDirection() throws IOException {
        List<String> violations = new ArrayList<>();
        
        requireNoImports("core", List.of(
                "import com.debugbridge.agent.",
                "import com.debugbridge.hooks.",
                "import com.debugbridge.fabric"
        ), violations);
        requireNoImports("hooks", List.of(
                "import com.debugbridge.core.",
                "import com.debugbridge.agent.",
                "import com.debugbridge.fabric"
        ), violations);
        requireNoImports("agent", List.of(
                "import com.debugbridge.fabric"
        ), violations);
        for (String fabricModule : List.of("fabric-1.19", "fabric-1.21.11", "fabric-26.2-dev")) {
            requireNoImports(fabricModule, List.of(
                    "import com.debugbridge.agent.",
                    "import com.debugbridge.hooks."
            ), violations);
        }
        
        assertEquals(List.of(), violations,
                "Module boundary violations should be fixed by moving through core interfaces or version-local adapters");
    }
    
    @Test
    void liveSmokeHarnessIsDiscoverableAndMcpFirst() throws IOException {
        Path script = repoRoot().resolve("tools/debugbridge-live-smoke.ps1");
        Path guide = repoRoot().resolve("docs/live-smoke.md");
        
        assertTrue(Files.isRegularFile(script), "tools/debugbridge-live-smoke.ps1 should prepare the live test run");
        assertTrue(Files.isRegularFile(guide), "docs/live-smoke.md should hold the MCP live-test recipe");
        
        String scriptText = Files.readString(script);
        assertTrue(scriptText.contains(":agent:test :core:test :hooks:jar :fabric-26.2-dev:jar"),
                "live smoke script should build the affected DebugBridge artifacts");
        assertTrue(scriptText.contains("debugbridge-26.2-snapshot-5"),
                "live smoke script should copy the 26.2 Fabric jar into the render-mod run folder");
        assertTrue(scriptText.contains("-Pdebugbridge.agent.jar="),
                "live smoke script should pass the built agent jar path into runClient");
        assertTrue(scriptText.contains("-Pdebugbridge.hooks.jar="),
                "live smoke script should pass the built hooks jar path into runClient");
        
        String guideText = Files.readString(guide).toLowerCase(Locale.ROOT);
        assertTrue(guideText.contains("mc_connect"), "live smoke guide should use MCP connection calls");
        assertTrue(guideText.contains("mc_logger"), "live smoke guide should use MCP logger calls");
        assertTrue(guideText.contains("mc_execute"), "live smoke guide should use MCP execute calls");
        assertFalse(guideText.contains("cli shim"), "live smoke guide should not route Minecraft actions through CLI shims");
    }

    @Test
    void latestSnapshotRegistersMcpRuntimeParityProviders() throws IOException {
        Path module = repoRoot().resolve("fabric-26.2-dev");
        Path mod = module.resolve("src/main/java/com/debugbridge/fabric262/DebugBridgeMod.java");

        assertTrue(Files.isRegularFile(module.resolve(
                        "src/main/java/com/debugbridge/fabric262/Minecraft262ScreenInspectProvider.java")),
                "26.2 should expose the screenInspect bridge endpoint used by mcdev-mcp 1.1");
        assertTrue(Files.isRegularFile(module.resolve(
                        "src/main/java/com/debugbridge/fabric262/Minecraft262ChatHistoryProvider.java")),
                "26.2 should expose the chatHistory bridge endpoint used by mcdev-mcp 1.1");

        String source = Files.readString(mod);
        assertTrue(source.contains("setScreenInspectProvider"),
                "26.2 should register a ScreenInspectProvider with BridgeServer");
        assertTrue(source.contains("setChatHistoryProvider"),
                "26.2 should register a ChatHistoryProvider with BridgeServer");
    }
}
