package com.debugbridge.core.lua;

import org.luaj.vm2.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Wraps a Java Class<?> as Lua userdata for static field/method access.
 * Returned by java.import().
 */
public class JavaClassWrapper extends LuaUserdata {
    private final Class<?> javaClass;
    private final String mojangClassName;
    private final JavaBridge bridge;

    public JavaClassWrapper(Class<?> javaClass, String mojangClassName, JavaBridge bridge) {
        super(javaClass);
        this.javaClass = javaClass;
        this.mojangClassName = mojangClassName;
        this.bridge = bridge;
    }

    public Class<?> getJavaClass() { return javaClass; }
    public String getMojangClassName() { return mojangClassName; }

    @Override
    public LuaValue get(LuaValue key) {
        String name = key.tojstring();

        // Try static field first
        String runtimeFieldName = bridge.getResolver().resolveField(mojangClassName, name);
        try {
            Field field = findStaticField(javaClass, runtimeFieldName);
            if (field == null && !runtimeFieldName.equals(name)) {
                field = findStaticField(javaClass, name);
            }
            if (field != null) {
                field.setAccessible(true);
                final Field f = field;
                Object value = bridge.getDispatcher().executeOnGameThread(
                    () -> f.get(null), 5000);
                return bridge.wrapJavaValue(value);
            }
        } catch (Exception e) {
            // Not a field, try method
        }

        // Static method
        return new MethodCallWrapper(null, javaClass, mojangClassName, name, bridge) {
            @Override
            public Varargs invoke(Varargs args) {
                // For static calls, the target is null and we only look at static methods
                return super.invoke(args);
            }
        };
    }

    private Field findStaticField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers())) return f;
            } catch (NoSuchFieldException e) {
                // continue
            }
        }
        return null;
    }

    @Override
    public LuaValue tostring() {
        return LuaValue.valueOf("Class<" + mojangClassName + ">");
    }
}
