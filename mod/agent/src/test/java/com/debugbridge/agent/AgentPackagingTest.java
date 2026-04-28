package com.debugbridge.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class AgentPackagingTest {
    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
    
    @Test
    void agentJarDoesNotEmbedBootstrapHooks() throws IOException {
        Path agentJar = Path.of(System.getProperty("debugbridge.agent.jar"));
        
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            assertTrue(
                    jar.stream().anyMatch(entry -> entry.getName().equals("com/debugbridge/agent/DebugBridgeAgent.class")),
                    "agent jar should contain the agent entry point"
            );
            assertFalse(
                    jar.stream().anyMatch(entry -> entry.getName().startsWith("com/debugbridge/hooks/")),
                    "bootstrap hook classes must only come from debugbridge-hooks.jar"
            );
        }
    }
    
    @Test
    void agentJarUsesAgentOwnedAdviceName() throws IOException {
        Path agentJar = Path.of(System.getProperty("debugbridge.agent.jar"));
        
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            assertTrue(
                    jar.stream().anyMatch(entry -> entry.getName().equals("com/debugbridge/agent/AgentLoggingAdvice.class")),
                    "agent jar should contain the agent-owned advice class"
            );
            assertFalse(
                    jar.stream().anyMatch(entry -> entry.getName().equals("com/debugbridge/agent/LoggingAdvice.class")),
                    "agent-owned advice must not use the ambiguous LoggingAdvice name"
            );
        }
    }
    
    @Test
    void noArgumentJavaAgentLaunchFindsAdjacentHooksJar() throws Exception {
        Path agentJar = Path.of(System.getProperty("debugbridge.agent.jar"));
        String bootClassPath;
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            bootClassPath = jar.getManifest().getMainAttributes().getValue("Boot-Class-Path");
        }
        
        assertTrue(Files.isRegularFile(agentJar.resolveSibling(bootClassPath)),
                "agent jar Boot-Class-Path should point at a built hooks jar next to the agent jar");
        
        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "-javaagent:" + agentJar,
                "-version"
        ).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        
        assertEquals(0, process.waitFor(), output);
    }
}
