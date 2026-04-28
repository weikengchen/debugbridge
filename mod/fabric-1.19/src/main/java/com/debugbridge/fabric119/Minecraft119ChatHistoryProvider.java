package com.debugbridge.fabric119;

import com.debugbridge.core.chat.ChatHistoryProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    public JsonArray getRecentMessages(int limit) throws Exception {
        JsonArray out = new JsonArray();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return out;
        ChatComponent chat = mc.gui.getChat();
        if (chat == null) return out;

        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) allMessagesField().get(chat);
        if (messages == null) return out;

        int n = Math.min(limit, messages.size());
        for (int i = 0; i < n; i++) {
            Object msg = messages.get(i);
            JsonObject obj = new JsonObject();
            Object content = messageGetter(msg.getClass()).invoke(msg);
            obj.addProperty("plain",
                content instanceof Component c ? c.getString() : String.valueOf(content));
            Field timeF = addedTimeField(msg.getClass());
            if (timeF != null) {
                obj.addProperty("addedTime", timeF.getInt(msg));
            }
            out.add(obj);
        }
        return out;
    }

    private static Field allMessagesField() throws NoSuchFieldException {
        Field f = allMessagesField;
        if (f != null) return f;
        f = ChatComponent.class.getDeclaredField("allMessages");
        f.setAccessible(true);
        allMessagesField = f;
        return f;
    }

    private static Method messageGetter(Class<?> cls) throws NoSuchMethodException {
        Method m = messageGetter;
        if (m != null && m.getDeclaringClass() == cls) return m;
        m = cls.getMethod("getMessage");
        messageGetter = m;
        return m;
    }

    private static Field addedTimeField(Class<?> cls) {
        Field f = addedTimeField;
        if (f != null && f.getDeclaringClass() == cls) return f;
        try {
            f = cls.getDeclaredField("addedTime");
            f.setAccessible(true);
            addedTimeField = f;
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
