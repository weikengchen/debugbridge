package com.debugbridge.core.lua;

import org.luaj.vm2.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * A callable Lua function that invokes a Java method via reflection.
 * Returned by JavaObjectWrapper.__index when no field matches.
 */
public class MethodCallWrapper extends org.luaj.vm2.lib.VarArgFunction {
    private final Object target;
    private final Class<?> targetClass;
    private final String mojangClass;
    private final String mojangMethodName;
    private final JavaBridge bridge;

    public MethodCallWrapper(Object target, Class<?> targetClass,
                             String mojangClass, String mojangMethodName, JavaBridge bridge) {
        this.target = target;
        this.targetClass = targetClass;
        this.mojangClass = mojangClass;
        this.mojangMethodName = mojangMethodName;
        this.bridge = bridge;
    }

    @Override
    public Varargs invoke(Varargs args) {
        try {
            // Detect and skip the implicit 'self' argument from Lua's ':' call syntax.
            // When called as obj:method(a, b), Lua passes (obj, a, b).
            // We need to strip the leading 'self' if it matches our target object.
            int startIdx = 0;
            int totalArgs = args.narg();
            if (totalArgs > 0 && target != null) {
                LuaValue firstArg = args.arg(1);
                if (firstArg instanceof JavaObjectWrapper wrapper
                        && wrapper.getJavaObject() == target) {
                    startIdx = 1; // skip self
                }
            }

            int nargs = totalArgs - startIdx;
            Object[] javaArgs = new Object[nargs];
            Class<?>[] argTypes = new Class<?>[nargs];
            for (int i = 0; i < nargs; i++) {
                LuaValue arg = args.arg(i + 1 + startIdx);
                javaArgs[i] = bridge.unwrapLuaValue(arg, null);
                argTypes[i] = javaArgs[i] == null ? null : javaArgs[i].getClass();
            }

            // Resolve method name through mappings
            String runtimeName = resolveMethodName();

            // Find the best matching method
            Method method = findBestMatch(targetClass, runtimeName, argTypes, nargs);
            if (method == null) {
                // Try with original name (for non-Minecraft methods like toString, hashCode)
                method = findBestMatch(targetClass, mojangMethodName, argTypes, nargs);
            }
            if (method == null) {
                throw new LuaError("No method '" + mojangMethodName + "' with " + nargs
                    + " args on " + mojangClass + suggestMethods());
            }

            method.setAccessible(true);

            // Convert args to match parameter types
            Object[] convertedArgs = convertArgs(javaArgs, method.getParameterTypes());

            final Method finalMethod = method;
            Object result = bridge.getDispatcher().executeOnGameThread(
                () -> finalMethod.invoke(target, convertedArgs), 5000);

            return bridge.wrapJavaValue(result);
        } catch (LuaError e) {
            throw e;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new LuaError("Method '" + mojangMethodName + "' threw: "
                + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
        } catch (Exception e) {
            throw new LuaError("Failed to call '" + mojangMethodName + "': " + e.getMessage());
        }
    }

    private String resolveMethodName() {
        // Walk class hierarchy trying to resolve through mappings
        for (Class<?> c = targetClass; c != null; c = c.getSuperclass()) {
            String mojClass = bridge.getResolver().unresolveClass(c.getName());
            String resolved = bridge.getResolver().resolveMethod(mojClass, mojangMethodName, null);
            if (!resolved.equals(mojangMethodName)) {
                return resolved;
            }
        }
        // Also check interfaces
        for (Class<?> iface : getAllInterfaces(targetClass)) {
            String mojClass = bridge.getResolver().unresolveClass(iface.getName());
            String resolved = bridge.getResolver().resolveMethod(mojClass, mojangMethodName, null);
            if (!resolved.equals(mojangMethodName)) {
                return resolved;
            }
        }
        return mojangMethodName;
    }

    private List<Class<?>> getAllInterfaces(Class<?> clazz) {
        List<Class<?>> interfaces = new ArrayList<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Class<?> iface : c.getInterfaces()) {
                if (!interfaces.contains(iface)) {
                    interfaces.add(iface);
                }
            }
        }
        return interfaces;
    }

    /**
     * Find the best matching method by name and argument count/types.
     * Walks the entire class hierarchy including interfaces.
     */
    private Method findBestMatch(Class<?> clazz, String name, Class<?>[] argTypes, int nargs) {
        // First pass: exact hierarchy walk
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == nargs) {
                    if (isCompatible(m.getParameterTypes(), argTypes)) {
                        return m;
                    }
                }
            }
        }
        // Check interfaces
        for (Class<?> iface : getAllInterfaces(clazz)) {
            for (Method m : iface.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == nargs) {
                    if (isCompatible(m.getParameterTypes(), argTypes)) {
                        return m;
                    }
                }
            }
        }
        // Relax: just match by name and arg count (ignore types)
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == nargs) {
                    return m;
                }
            }
        }
        return null;
    }

    private boolean isCompatible(Class<?>[] paramTypes, Class<?>[] argTypes) {
        for (int i = 0; i < paramTypes.length; i++) {
            if (argTypes[i] == null) continue; // null matches any reference type
            Class<?> param = boxType(paramTypes[i]);
            Class<?> arg = boxType(argTypes[i]);
            if (!param.isAssignableFrom(arg) && !isNumericCompatible(param, arg)) {
                return false;
            }
        }
        return true;
    }

    private boolean isNumericCompatible(Class<?> param, Class<?> arg) {
        return Number.class.isAssignableFrom(param) && Number.class.isAssignableFrom(arg);
    }

    private Class<?> boxType(Class<?> t) {
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == boolean.class) return Boolean.class;
        if (t == byte.class) return Byte.class;
        if (t == short.class) return Short.class;
        if (t == char.class) return Character.class;
        return t;
    }

    private Object[] convertArgs(Object[] javaArgs, Class<?>[] paramTypes) {
        Object[] result = new Object[javaArgs.length];
        for (int i = 0; i < javaArgs.length; i++) {
            result[i] = convertArg(javaArgs[i], paramTypes[i]);
        }
        return result;
    }

    private Object convertArg(Object arg, Class<?> targetType) {
        if (arg == null) return null;
        if (targetType.isInstance(arg)) return arg;

        // Numeric conversions
        if (arg instanceof Number num) {
            if (targetType == int.class || targetType == Integer.class) return num.intValue();
            if (targetType == long.class || targetType == Long.class) return num.longValue();
            if (targetType == float.class || targetType == Float.class) return num.floatValue();
            if (targetType == double.class || targetType == Double.class) return num.doubleValue();
            if (targetType == byte.class || targetType == Byte.class) return num.byteValue();
            if (targetType == short.class || targetType == Short.class) return num.shortValue();
        }

        // String to char
        if (arg instanceof String str && (targetType == char.class || targetType == Character.class)) {
            if (!str.isEmpty()) return str.charAt(0);
        }

        return arg;
    }

    private String suggestMethods() {
        // Collect available method names for error message
        List<String> names = new ArrayList<>();
        for (Class<?> c = targetClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String mojName = bridge.getResolver().unresolveClass(c.getName());
                // Just list the runtime names for now
                String display = m.getName() + "(" + m.getParameterCount() + " args)";
                if (!names.contains(display) && !Modifier.isPrivate(m.getModifiers())) {
                    names.add(display);
                }
            }
        }
        if (names.isEmpty()) return "";
        if (names.size() > 10) {
            return "\n  Available methods (first 10): " + String.join(", ", names.subList(0, 10)) + "...";
        }
        return "\n  Available methods: " + String.join(", ", names);
    }
}
