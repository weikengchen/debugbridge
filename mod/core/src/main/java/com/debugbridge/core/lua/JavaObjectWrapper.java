package com.debugbridge.core.lua;

import org.luaj.vm2.*;

/**
 * Wraps a Java object as Lua userdata with metamethods for field/method access.
 * All name lookups go through the MappingResolver for transparent obfuscation handling.
 */
public class JavaObjectWrapper extends LuaUserdata {
    private final Object javaObject;
    private final Class<?> declaredType;
    private final String mojangTypeName;
    private final JavaBridge bridge;

    public JavaObjectWrapper(Object javaObject, Class<?> declaredType, String mojangTypeName, JavaBridge bridge) {
        super(javaObject);
        this.javaObject = javaObject;
        this.declaredType = declaredType;
        this.mojangTypeName = mojangTypeName;
        this.bridge = bridge;
    }

    public Object getJavaObject() { return javaObject; }
    public Class<?> getDeclaredType() { return declaredType; }
    public String getMojangTypeName() { return mojangTypeName; }

    @Override
    public LuaValue get(LuaValue key) {
        if (javaObject == null) {
            throw new LuaError("Attempted to access '" + key.tojstring() + "' on a null object");
        }

        String name = key.tojstring();

        // 1. Try field access first — walk class hierarchy
        try {
            java.lang.reflect.Field field = findField(declaredType, name);
            if (field != null) {
                field.setAccessible(true);
                Object value = bridge.getDispatcher().executeOnGameThread(
                    () -> field.get(javaObject), 5000);
                return bridge.wrapJavaValue(value);
            }
        } catch (Exception e) {
            // Not a field, try method
        }

        // 2. Return a callable method wrapper
        return new MethodCallWrapper(javaObject, declaredType, mojangTypeName, name, bridge);
    }

    @Override
    public void set(LuaValue key, LuaValue value) {
        if (javaObject == null) {
            throw new LuaError("Attempted to set field on a null object");
        }

        String name = key.tojstring();
        try {
            java.lang.reflect.Field field = findField(declaredType, name);
            if (field == null) {
                throw new LuaError("No field '" + name + "' on " + mojangTypeName);
            }
            field.setAccessible(true);
            Object javaValue = bridge.unwrapLuaValue(value, field.getType());
            bridge.getDispatcher().executeOnGameThread(() -> {
                field.set(javaObject, javaValue);
                return null;
            }, 5000);
        } catch (LuaError e) {
            throw e;
        } catch (Exception e) {
            throw new LuaError("Failed to set field '" + name + "': " + e.getMessage());
        }
    }

    @Override
    public LuaValue tostring() {
        if (javaObject == null) return LuaValue.valueOf("null");
        return LuaValue.valueOf(mojangTypeName + "@" + Integer.toHexString(System.identityHashCode(javaObject)));
    }

    /**
     * Find a field by Mojang name, resolving through mappings and walking the hierarchy.
     */
    private java.lang.reflect.Field findField(Class<?> clazz, String mojangName) {
        // Walk up the class hierarchy
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            String mojangClass = bridge.getResolver().unresolveClass(c.getName());
            String runtimeName = bridge.getResolver().resolveField(mojangClass, mojangName);

            try {
                return c.getDeclaredField(runtimeName);
            } catch (NoSuchFieldException e) {
                // Try next in hierarchy
            }

            // Also try the original name directly (for non-mapped fields)
            if (!runtimeName.equals(mojangName)) {
                try {
                    return c.getDeclaredField(mojangName);
                } catch (NoSuchFieldException e) {
                    // Try next
                }
            }
        }
        return null;
    }
}
