package com.debugbridge.fabric12111;

import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.mapping.MappingResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.ComponentSerialization;

import java.lang.reflect.Field;
import java.util.List;

public class Minecraft12111ChatHistoryProvider implements ChatHistoryProvider {
    
    private static volatile Field allMessagesField;
    
    private static Field allMessagesField(MappingResolver resolver) throws NoSuchFieldException {
        Field f = allMessagesField;
        if (f != null) return f;
        String runtime = resolver.resolveField(
                "net.minecraft.client.gui.components.ChatComponent", "allMessages");
        f = ChatComponent.class.getDeclaredField(runtime);
        f.setAccessible(true);
        allMessagesField = f;
        return f;
    }
    
    @Override
    public JsonArray getRecentMessages(int limit, MappingResolver resolver, boolean includeJson) throws Exception {
        JsonArray out = new JsonArray();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return out;
        ChatComponent chat = mc.gui.getChat();
        if (chat == null) return out;
        
        @SuppressWarnings("unchecked")
        List<GuiMessage> messages = (List<GuiMessage>) allMessagesField(resolver).get(chat);
        if (messages == null) return out;
        
        // ChatComponent stores newest-first; honor that.
        int n = Math.min(limit, messages.size());
        for (int i = 0; i < n; i++) {
            GuiMessage msg = messages.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("plain", msg.content().getString());
            obj.addProperty("addedTime", msg.addedTime());
            if (includeJson) {
                try {
                    JsonElement json = ComponentSerialization.CODEC
                        .encodeStart(JsonOps.INSTANCE, msg.content())
                        .getOrThrow();
                    obj.add("json", json);
                } catch (Exception ignore) {
                    // Component contains a registry-bound element JsonOps can't
                    // resolve (rare for chat). Skip the json field; plain is
                    // still there.
                }
            }
            out.add(obj);
        }
        return out;
    }
}
