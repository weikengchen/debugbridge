package com.debugbridge.core;

import com.debugbridge.core.lua.DirectDispatcher;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.server.BridgeServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the actionable error messages added when Lua tries to
 * call a Java userdata as if it were a function. These are the most common
 * mistakes in practice — Mojang 1.21.x has many fields that shadow same-named
 * getter methods, and users coming from Python/JS expect class(args) to work
 * as a constructor.
 */
class CallErrorHintTest {
    private static final int PORT = 19883;
    private static BridgeServer server;
    private TestClient client;
    
    @BeforeAll
    static void startServer() throws Exception {
        server = new BridgeServer(PORT,
                new PassthroughResolver("test"),
                new DirectDispatcher());
        server.start();
        Thread.sleep(500);
    }
    
    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }
    
    @BeforeEach
    void connect() throws Exception {
        client = new TestClient(new URI("ws://127.0.0.1:" + PORT));
        assertTrue(client.connectBlocking(3, TimeUnit.SECONDS));
    }
    
    @AfterEach
    void disconnect() throws Exception {
        if (client != null) client.closeBlocking();
    }
    
    @Test
    void testCallingFieldAsMethodGivesActionableError() throws Exception {
        JsonObject resp = execute("""
                local Outer = java.import("com.debugbridge.core.CallErrorHintTest$Outer")
                local o = java.new(Outer)
                o:inner()
                """);
        assertFalse(resp.get("success").getAsBoolean(), "Should fail");
        String error = resp.get("error").getAsString();
        System.out.println("obj:field() error:\n" + error);
        
        // Must identify this as a Java-object-call, not LuaJ's generic mumble.
        assertTrue(error.contains("Java object"),
                "Error should mention it's a Java object, got: " + error);
        // Must name the field and the parent type.
        assertTrue(error.contains("inner"),
                "Error should mention the field name 'inner', got: " + error);
        assertTrue(error.contains("Outer") || error.contains("CallErrorHintTest"),
                "Error should mention the parent type, got: " + error);
        // Must mention that it's a FIELD (not a method).
        assertTrue(error.contains("FIELD") || error.contains("field"),
                "Error should say it's a field, got: " + error);
        // Must give the exact corrected syntax.
        assertTrue(error.contains("obj.inner") || error.contains(".inner."),
                "Error should suggest obj.inner.<sub> syntax, got: " + error);
    }
    
    @Test
    void testCallingFieldAsMethodDetectsColonCall() throws Exception {
        JsonObject resp = execute("""
                local Outer = java.import("com.debugbridge.core.CallErrorHintTest$Outer")
                local o = java.new(Outer)
                o:inner()
                """);
        String error = resp.get("error").getAsString();
        // The colon-call detection should fire because the first "arg" passed
        // to the invoked wrapper is the parent Outer wrapper.
        assertTrue(error.contains("obj:") || error.contains("obj.inner"),
                "Should explain the colon-call desugaring, got: " + error);
    }
    
    // ==================== obj:field() mistake ====================
    
    @Test
    void testFieldChainAfterFieldAccessStillWorks() throws Exception {
        // Field chaining (the suggested fix) should still work normally.
        JsonObject resp = execute("""
                local Outer = java.import("com.debugbridge.core.CallErrorHintTest$Outer")
                local o = java.new(Outer)
                return o.inner.value
                """);
        assertTrue(resp.get("success").getAsBoolean(),
                "Field chaining should work: " + resp);
        assertEquals(42,
                resp.get("result").getAsJsonObject().get("value").getAsInt());
    }
    
    @Test
    void testCallingClassWrapperAsConstructorGivesActionableError() throws Exception {
        JsonObject resp = execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = ArrayList()
                """);
        assertFalse(resp.get("success").getAsBoolean(), "Should fail");
        String error = resp.get("error").getAsString();
        System.out.println("Class() error:\n" + error);
        
        assertTrue(error.contains("ArrayList"),
                "Should name the class, got: " + error);
        assertTrue(error.contains("java.new"),
                "Should tell the user to use java.new, got: " + error);
    }
    
    @Test
    void testCallingClassWrapperWithArgsGivesActionableError() throws Exception {
        JsonObject resp = execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = ArrayList(10)
                """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("java.new"),
                "Should recommend java.new, got: " + error);
    }
    
    // ==================== class(args) mistake ====================
    
    private JsonObject execute(String code) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("id", "hint_" + System.nanoTime());
        req.addProperty("type", "execute");
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        req.add("payload", payload);
        
        client.send(new Gson().toJson(req));
        String response = client.responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "No response within 5s");
        return JsonParser.parseString(response).getAsJsonObject();
    }
    
    /**
     * Public helper class used as the target of field-shadows-method tests.
     * Mimics the {@code Entity.level} / {@code Entity.level()} pattern that
     * causes the in-game error: a same-named field of Object type wins over
     * the method in {@link com.debugbridge.core.lua.JavaObjectWrapper}'s
     * field-first resolution order.
     */
    public static class Outer {
        public final Inner inner = new Inner();
        
        @SuppressWarnings("unused")  // Accessed reflectively through the bridge
        public Inner inner() {
            return inner;
        }
    }
    
    // ==================== Helpers ====================
    
    public static class Inner {
        public final int value = 42;
    }
    
    private static class TestClient extends WebSocketClient {
        final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();
        
        TestClient(URI uri) {
            super(uri);
        }
        
        @Override
        public void onOpen(ServerHandshake h) {
        }
        
        @Override
        public void onMessage(String msg) {
            responses.offer(msg);
        }
        
        @Override
        public void onClose(int c, String r, boolean rem) {
        }
        
        @Override
        public void onError(Exception e) {
            e.printStackTrace();
        }
    }
}
