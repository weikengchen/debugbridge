package com.debugbridge.fabric12111;

import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.mapping.ParsedMappings;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.*;

/**
 * MappingResolver for Fabric 1.21.11 that resolves Mojang names to intermediary names.
 *
 * Class resolution: ProGuard (Mojang→obfuscated) + Fabric (obfuscated→intermediary)
 *
 * Method/field resolution: Uses Fabric's mapMethodName/mapFieldName with the obfuscated
 * names from ProGuard. Since Fabric only maps methods on the declaring class/interface
 * (not subclasses), we walk the ProGuard hierarchy to find which class originally declares
 * the method, then query Fabric with that class's obfuscated name.
 *
 * Fallback: descriptor-based matching against runtime methods via reflection.
 */
public class FabricMojangResolver implements MappingResolver {
    private final String version;
    private final ParsedMappings mappings;
    private final net.fabricmc.loader.api.MappingResolver fabricResolver;
    private final Map<String, String> intermediaryToMojang = new HashMap<>();
    private final Map<String, String> methodCache = new HashMap<>();
    private final Map<String, String> fieldCache = new HashMap<>();

    public FabricMojangResolver(String version, ParsedMappings mappings) {
        this.version = version;
        this.mappings = mappings;
        this.fabricResolver = FabricLoader.getInstance().getMappingResolver();

        // Build reverse cache: for each Mojang class, resolve to intermediary and cache reverse
        for (String mojangName : mappings.classes.keySet()) {
            try {
                String intermediary = resolveClass(mojangName);
                if (!intermediary.equals(mojangName)) {
                    intermediaryToMojang.put(intermediary, mojangName);
                }
            } catch (Exception e) {
                // Skip classes that fail to resolve
            }
        }
    }

    @Override
    public String resolveClass(String mojangClassName) {
        String obfuscated = mappings.classes.getOrDefault(mojangClassName, mojangClassName);
        try {
            return fabricResolver.mapClassName("official", obfuscated);
        } catch (Exception e) {
            return obfuscated;
        }
    }

    @Override
    public String resolveField(String mojangClassName, String mojangFieldName) {
        String cacheKey = mojangClassName + "." + mojangFieldName;
        String cached = fieldCache.get(cacheKey);
        if (cached != null) return cached;

        // Get the obfuscated field name and type from ProGuard
        Map<String, String> classFields = mappings.fields.get(mojangClassName);
        if (classFields == null) return mojangFieldName;
        String obfFieldName = classFields.get(mojangFieldName);
        if (obfFieldName == null) return mojangFieldName;

        Map<String, String> types = mappings.fieldTypes.get(mojangClassName);
        String mojangType = types != null ? types.get(mojangFieldName) : null;

        // Try Fabric mapping: walk the ProGuard class hierarchy to find declaring class
        String result = tryFabricFieldMapping(mojangClassName, mojangFieldName, obfFieldName, mojangType);
        if (result != null) {
            fieldCache.put(cacheKey, result);
            return result;
        }

        // Fallback: descriptor-based matching
        if (mojangType != null) {
            result = matchFieldByDescriptor(mojangClassName, mojangType);
            if (result != null) {
                fieldCache.put(cacheKey, result);
                return result;
            }
        }

        return obfFieldName;
    }

    @Override
    public String resolveMethod(String mojangClassName, String mojangMethodName, String[] mojangParamTypes) {
        String cacheKey = mojangClassName + "." + mojangMethodName
            + (mojangParamTypes != null ? "(" + String.join(",", mojangParamTypes) + ")" : "");
        String cached = methodCache.get(cacheKey);
        if (cached != null) return cached;

        // Find the method in ProGuard mappings
        Map<String, String> classMethods = mappings.methods.get(mojangClassName);
        if (classMethods == null) return mojangMethodName;

        String obfMethodName = null;
        String proguardDesc = null;
        String matchedKey = null;

        Map<String, String> descriptors = mappings.methodDescriptors.get(mojangClassName);
        if (descriptors != null) {
            if (mojangParamTypes != null) {
                String key = mojangMethodName + "(" + String.join(",", mojangParamTypes) + ")";
                obfMethodName = classMethods.get(key);
                proguardDesc = descriptors.get(key);
                matchedKey = key;
            }
            if (obfMethodName == null) {
                String prefix = mojangMethodName + "(";
                for (Map.Entry<String, String> entry : classMethods.entrySet()) {
                    if (entry.getKey().startsWith(prefix)) {
                        obfMethodName = entry.getValue();
                        proguardDesc = descriptors.get(entry.getKey());
                        matchedKey = entry.getKey();
                        break;
                    }
                }
            }
        }

        if (obfMethodName == null) return mojangMethodName;

        // Strategy 1: Use Fabric's mapMethodName with obfuscated names.
        // Walk the ProGuard class hierarchy to find where the method is originally declared,
        // since Fabric only maps methods on the declaring class/interface.
        if (proguardDesc != null) {
            String obfJvmDesc = toObfuscatedJvmMethodDescriptor(proguardDesc);
            String result = tryFabricMethodMapping(mojangClassName, mojangMethodName,
                matchedKey, obfMethodName, obfJvmDesc);
            if (result != null) {
                methodCache.put(cacheKey, result);
                return result;
            }
        }

        // Strategy 2: Descriptor-based matching against runtime class methods.
        if (proguardDesc != null) {
            String intermediaryJvmDesc = toIntermediaryJvmMethodDescriptor(proguardDesc);
            String intermediaryClass = resolveClass(mojangClassName);
            String result = matchMethodByDescriptor(intermediaryClass, intermediaryJvmDesc);
            if (result != null) {
                methodCache.put(cacheKey, result);
                return result;
            }
        }

        return mojangMethodName;
    }

    /**
     * Ask Fabric to translate (mojangClassName.obfMethodName / obfJvmDesc) from the
     * "official" namespace into the runtime intermediary namespace.
     *
     * Critically, this is STRICT — it only consults the originally-passed Mojang
     * class. An earlier version walked every class in the ProGuard mappings that
     * declared a method with the same name; that produced confidently-wrong
     * answers because methods sharing a name on unrelated classes resolve to
     * different intermediary names. The hierarchy walk for inherited methods is
     * the {@link com.debugbridge.core.lua.MethodCallWrapper}'s job (it walks the
     * runtime class+interface graph and calls this resolver once per Mojang
     * ancestor). Trying to be clever here drops names from the wrong class on the
     * floor.
     */
    private String tryFabricMethodMapping(String mojangClassName, String mojangMethodName,
                                           String methodKey, String obfMethodName, String obfJvmDesc) {
        String obfClass = mappings.classes.getOrDefault(mojangClassName, mojangClassName);
        try {
            String mapped = fabricResolver.mapMethodName("official", obfClass, obfMethodName, obfJvmDesc);
            if (!mapped.equals(obfMethodName)) {
                return mapped;
            }
        } catch (Exception e) {
            // not mapped on this class; caller will walk to parents/interfaces
        }
        return null;
    }

    /**
     * Try Fabric's mapFieldName on the declaring class.
     */
    private String tryFabricFieldMapping(String mojangClassName, String mojangFieldName,
                                          String obfFieldName, String mojangType) {
        String obfClass = mappings.classes.getOrDefault(mojangClassName, mojangClassName);
        if (mojangType != null) {
            String obfDesc = toJvmDescriptor(obfuscateTypeName(mojangType));
            try {
                String mapped = fabricResolver.mapFieldName("official", obfClass, obfFieldName, obfDesc);
                if (!mapped.equals(obfFieldName)) {
                    return mapped;
                }
            } catch (Exception e) {
                // fall through
            }
        }
        return null;
    }

    /**
     * Match a method by JVM descriptor against runtime class's declared methods.
     * Only used as fallback when Fabric mapping fails.
     */
    private String matchMethodByDescriptor(String intermediaryClass, String intermediaryJvmDesc) {
        try {
            Class<?> cls = Class.forName(intermediaryClass);
            List<Method> matches = new ArrayList<>();
            for (Method m : cls.getDeclaredMethods()) {
                if (getMethodDescriptor(m).equals(intermediaryJvmDesc)) {
                    matches.add(m);
                }
            }
            if (matches.size() == 1) {
                return matches.get(0).getName();
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    /**
     * Match a field by type descriptor against runtime class's declared fields.
     */
    private String matchFieldByDescriptor(String mojangClassName, String mojangType) {
        String intermediaryClass = resolveClass(mojangClassName);
        String intermediaryDesc = toJvmDescriptor(resolveTypeName(mojangType));
        try {
            Class<?> cls = Class.forName(intermediaryClass);
            List<java.lang.reflect.Field> matches = new ArrayList<>();
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (classToDescriptor(f.getType()).equals(intermediaryDesc)) {
                    matches.add(f);
                }
            }
            if (matches.size() == 1) {
                return matches.get(0).getName();
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    @Override
    public String unresolveClass(String runtimeClassName) {
        String cached = intermediaryToMojang.get(runtimeClassName);
        if (cached != null) return cached;

        try {
            String obfuscated = fabricResolver.unmapClassName("official", runtimeClassName);
            String mojang = mappings.classesReverse.getOrDefault(obfuscated, runtimeClassName);
            if (!mojang.equals(runtimeClassName)) {
                intermediaryToMojang.put(runtimeClassName, mojang);
            }
            return mojang;
        } catch (Exception e) {
            return runtimeClassName;
        }
    }

    @Override
    public Collection<String> getAllClassNames() {
        return mappings.classes.keySet();
    }

    @Override
    public Collection<String> getFieldNames(String mojangClassName) {
        Map<String, String> classFields = mappings.fields.get(mojangClassName);
        return classFields != null ? classFields.keySet() : Collections.emptyList();
    }

    @Override
    public Collection<String> getMethodSignatures(String mojangClassName) {
        Map<String, String> classMethods = mappings.methods.get(mojangClassName);
        return classMethods != null ? classMethods.keySet() : Collections.emptyList();
    }

    @Override
    public String getVersion() { return version; }

    @Override
    public boolean isObfuscated() { return true; }

    // ==================== Type resolution helpers ====================

    private String resolveTypeName(String mojangType) {
        if (mojangType.endsWith("[]")) {
            return resolveTypeName(mojangType.substring(0, mojangType.length() - 2)) + "[]";
        }
        switch (mojangType) {
            case "void": case "boolean": case "byte": case "char":
            case "short": case "int": case "long": case "float": case "double":
                return mojangType;
        }
        return resolveClass(mojangType);
    }

    private String obfuscateTypeName(String mojangType) {
        if (mojangType.endsWith("[]")) {
            return obfuscateTypeName(mojangType.substring(0, mojangType.length() - 2)) + "[]";
        }
        return mappings.classes.getOrDefault(mojangType, mojangType);
    }

    // ==================== Descriptor conversion ====================

    private String toIntermediaryJvmMethodDescriptor(String proguardDesc) {
        int closeParen = proguardDesc.indexOf(')');
        String params = proguardDesc.substring(1, closeParen);
        String returnType = proguardDesc.substring(closeParen + 1);

        StringBuilder sb = new StringBuilder("(");
        if (!params.isEmpty()) {
            for (String param : splitTypeList(params)) {
                sb.append(toJvmDescriptor(resolveTypeName(param.trim())));
            }
        }
        sb.append(")");
        sb.append(toJvmDescriptor(resolveTypeName(returnType)));
        return sb.toString();
    }

    private String toObfuscatedJvmMethodDescriptor(String proguardDesc) {
        int closeParen = proguardDesc.indexOf(')');
        String params = proguardDesc.substring(1, closeParen);
        String returnType = proguardDesc.substring(closeParen + 1);

        StringBuilder sb = new StringBuilder("(");
        if (!params.isEmpty()) {
            for (String param : splitTypeList(params)) {
                sb.append(toJvmDescriptor(obfuscateTypeName(param.trim())));
            }
        }
        sb.append(")");
        sb.append(toJvmDescriptor(obfuscateTypeName(returnType)));
        return sb.toString();
    }

    private List<String> splitTypeList(String types) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < types.length(); i++) {
            char c = types.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(types.substring(start, i));
                start = i + 1;
            }
        }
        result.add(types.substring(start));
        return result;
    }

    private String getMethodDescriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(classToDescriptor(p));
        }
        sb.append(")");
        sb.append(classToDescriptor(m.getReturnType()));
        return sb.toString();
    }

    private String classToDescriptor(Class<?> cls) {
        if (cls == void.class) return "V";
        if (cls == boolean.class) return "Z";
        if (cls == byte.class) return "B";
        if (cls == char.class) return "C";
        if (cls == short.class) return "S";
        if (cls == int.class) return "I";
        if (cls == long.class) return "J";
        if (cls == float.class) return "F";
        if (cls == double.class) return "D";
        if (cls.isArray()) return "[" + classToDescriptor(cls.getComponentType());
        return "L" + cls.getName().replace('.', '/') + ";";
    }

    private String toJvmDescriptor(String typeName) {
        if (typeName.endsWith("[]")) {
            return "[" + toJvmDescriptor(typeName.substring(0, typeName.length() - 2));
        }
        return switch (typeName) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + typeName.replace('.', '/') + ";";
        };
    }
}
