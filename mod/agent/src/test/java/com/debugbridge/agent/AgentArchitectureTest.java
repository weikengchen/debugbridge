package com.debugbridge.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentArchitectureTest {
    private static String readMainSource(String fileName) throws IOException {
        return Files.readString(mainSourceDir().resolve(fileName));
    }
    
    private static String readMainSourceIfPresent(String fileName) throws IOException {
        Path path = mainSourceDir().resolve(fileName);
        return Files.exists(path) ? Files.readString(path) : "";
    }
    
    private static Path mainSourceDir() {
        return Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/com/debugbridge/agent");
    }
    
    @Test
    void debugBridgeAgentDelegatesByteBuddyInjectionDetails() throws IOException {
        String source = readMainSource("DebugBridgeAgent.java");
        
        assertTrue(
                source.contains("new AdviceInjector("),
                "DebugBridgeAgent should delegate injection policy to AdviceInjector"
        );
        assertFalse(source.contains("import net.bytebuddy.agent.builder.AgentBuilder;"));
        assertFalse(source.contains("import net.bytebuddy.asm.Advice;"));
        assertFalse(source.contains("import net.bytebuddy.dynamic.ClassFileLocator;"));
        assertFalse(source.contains("import net.bytebuddy.dynamic.DynamicType;"));
        assertFalse(source.contains("import net.bytebuddy.dynamic.scaffold.TypeValidation;"));
        assertFalse(source.contains("import net.bytebuddy.pool.TypePool;"));
        assertFalse(source.contains("import net.bytebuddy.utility.JavaModule;"));
    }
    
    @Test
    void adviceClassNameMakesClassloaderOwnershipExplicit() throws IOException {
        String sources = readMainSource("DebugBridgeAgent.java") + "\n"
                + readMainSourceIfPresent("AdviceInjector.java");
        
        assertTrue(
                sources.contains("AgentLoggingAdvice.class"),
                "agent transformations should use the agent-owned advice class"
        );
        assertFalse(
                sources.contains("Advice.to(LoggingAdvice.class)"),
                "ambiguous LoggingAdvice name makes the agent/hooks classloader boundary easy to break"
        );
    }
}
