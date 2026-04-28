package com.debugbridge.fabric119;

import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.mapping.MappingResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class Minecraft119ChatHistoryProvider implements ChatHistoryProvider {

    private static volatile Field allMessagesField;
    private static volatile Method messageGetter;
    private static volatile Field addedTimeField;

    @Override
    public JsonArray getRecentMessages(int limit, MappingResolver resolver, boolean includeJson) throws Exception {
        JsonArray out = new JsonArray();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return out;
        ChatComponent chat = mc.gui.getChat();
        if (chat == null) return out;

        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) allMessagesField(resolver).get(chat);
        if (messages == null) return out;

        int n = Math.min(limit, messages.size());
        for (int i = 0; i < n; i++) {
            Object msg = messages.get(i);
            JsonObject obj = new JsonObject();
            Object content = messageGetter(msg.getClass(), resolver).invoke(msg);
            obj.addProperty("plain",
                content instanceof Component c ? c.getString() : String.valueOf(content));
            Field timeF = addedTimeField(msg.getClass(), resolver);
            if (timeF != null) {
                obj.addProperty("addedTime", timeF.getInt(msg));
            }
            if (includeJson && content instanceof Component c) {
                try {
                    String jsonStr = Component.Serializer.toJson(c);
                    obj.add("json", JsonParser.parseString(jsonStr));
                } catch (Exception ignore) {
                    // Skip json field on serialization failure.
                }
            }
            out.add(obj);
        }
        return out;
    }

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

    private static Method messageGetter(Class<?> cls, MappingResolver resolver) throws NoSuchMethodException {
        Method m = messageGetter;
        if (m != null && m.getDeclaringClass() == cls) return m;
        String mojangCls = resolver.unresolveClass(cls.getName());
        String runtime = resolver.resolveMethod(mojangCls != null ? mojangCls : cls.getName(),
            "getMessage", null);
        m = cls.getMethod(runtime);
        messageGetter = m;
        return m;
    }

    private static Field addedTimeField(Class<?> cls, MappingResolver resolver) {
        Field f = addedTimeField;
        if (f != null && f.getDeclaringClass() == cls) return f;
        String mojangCls = resolver.unresolveClass(cls.getName());
        String runtime = resolver.resolveField(mojangCls != null ? mojangCls : cls.getName(),
            "addedTime");
        try {
            f = cls.getDeclaredField(runtime);
            f.setAccessible(true);
            addedTimeField = f;
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
