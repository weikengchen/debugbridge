import { bridge } from './bridge';

/**
 * Helper functions that generate Lua code for object inspection
 */

export interface FieldInfo {
  name: string;
  type: string;
  value: unknown;
  valueType: 'primitive' | 'string' | 'object' | 'array' | 'null';
  className?: string;
  expandable: boolean;
  isStatic?: boolean;
  modifiers?: string;
}

export interface ObjectInfo {
  className: string;
  shortName: string;
  fields: FieldInfo[];
  methods: string[];
  superClass?: string;
  interfaces?: string[];
  isNull: boolean;
  displayValue?: string;
}

/**
 * Import a class and get its static fields/methods
 */
export async function inspectClass(className: string): Promise<ObjectInfo> {
  const code = `
    local class = java.import("${className}")
    if class == nil then
      return { isNull = true, className = "${className}" }
    end
    return java.describe(class)
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to inspect class');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result), className);
}

/**
 * Call a static method on a class
 */
export async function callStaticMethod(className: string, methodName: string, args: string[] = []): Promise<ObjectInfo> {
  const argsStr = args.join(', ');
  const code = `
    local class = java.import("${className}")
    local result = class:${methodName}(${argsStr})
    if result == nil then
      return { isNull = true }
    end
    return java.describe(result)
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to call method');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result));
}

/**
 * Get a field value from an object path
 */
export async function getFieldValue(basePath: string, fieldName: string): Promise<ObjectInfo> {
  const code = `
    local obj = ${basePath}
    if obj == nil then
      return { isNull = true }
    end
    local value = obj.${fieldName}
    if value == nil then
      return { isNull = true }
    end
    return java.describe(value)
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to get field');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result));
}

/**
 * Call a method on an object
 */
export async function callMethod(basePath: string, methodName: string, args: string[] = []): Promise<ObjectInfo> {
  const argsStr = args.join(', ');
  const code = `
    local obj = ${basePath}
    if obj == nil then
      return { isNull = true }
    end
    local result = obj:${methodName}(${argsStr})
    if result == nil then
      return { isNull = true }
    end
    return java.describe(result)
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to call method');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result));
}

/**
 * Evaluate arbitrary Lua and describe the result.
 *
 * java.describe(obj) returns { class, runtimeClass, superclass, interfaces,
 * fields = [ {name, type, static, final}, ... ] } — metadata only, no values.
 * We read each field's value from the object and package everything into the
 * format parseObjectInfo expects.
 */
export async function evaluateAndDescribe(luaCode: string): Promise<ObjectInfo> {
  const code = `
    local __obj = (function()
      ${luaCode}
    end)()
    if __obj == nil then
      return {isNull = true}
    end

    local __result = {}

    local __dok, __desc = pcall(java.describe, __obj)
    if __dok and __desc then
      __result.__class = tostring(__desc.class)
      __result.superClass = tostring(__desc.superclass or "")

      __result.fields = {}
      local __fc = 0
      for _, __f in ipairs(__desc.fields or {}) do
        __fc = __fc + 1
        if __fc > 200 then break end
        local __fi = {
          type = tostring(__f.type or "unknown"),
          modifiers = (__f.static and "static " or "") .. (__f.final and "final" or "")
        }
        local __vok, __val = pcall(function() return __obj[__f.name] end)
        if __vok and __val ~= nil then
          local __vt = type(__val)
          if __vt == "string" then
            __fi.value = #__val > 100 and __val:sub(1, 100) .. "..." or __val
            __fi.valueClass = "java.lang.String"
          elseif __vt == "number" then
            __fi.value = __val
          elseif __vt == "boolean" then
            __fi.value = __val
          elseif __vt == "userdata" then
            local __tcok, __vc = pcall(java.typeof, __val)
            __fi.valueClass = __tcok and tostring(__vc) or "Object"
            local __tsok, __ts = pcall(tostring, __val)
            __fi.value = {__class = __fi.valueClass}
            if __tsok and __ts then
              __fi.value.__toString = #__ts > 80 and __ts:sub(1, 80) .. "..." or __ts
            end
          elseif __vt == "table" then
            __fi.value = {__class = "table"}
            __fi.valueClass = "table"
          end
        end
        __result.fields[__f.name] = __fi
      end
    else
      -- Fallback for non-Java objects (plain Lua tables etc.)
      __result.__class = type(__obj)
      __result.fields = {}
      if type(__obj) == "table" then
        local __fc = 0
        for __k, __v in pairs(__obj) do
          __fc = __fc + 1
          if __fc > 200 then break end
          local __fi = {type = type(__v)}
          if type(__v) == "string" then
            __fi.value = #__v > 100 and __v:sub(1, 100) .. "..." or __v
          elseif type(__v) == "number" or type(__v) == "boolean" then
            __fi.value = __v
          elseif type(__v) == "table" or type(__v) == "userdata" then
            __fi.value = {__class = type(__v)}
          end
          __result.fields[tostring(__k)] = __fi
        end
      end
    end

    local __tok, __tstr = pcall(tostring, __obj)
    if __tok and __tstr then
      __result.__toString = #__tstr > 80 and __tstr:sub(1, 80) .. "..." or __tstr
    end

    return __result
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to evaluate');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result));
}

/**
 * Get array/collection elements
 */
export async function getCollectionElements(basePath: string, start: number = 0, limit: number = 50): Promise<{ items: ObjectInfo[], total: number }> {
  const code = `
    local obj = ${basePath}
    if obj == nil then
      return { items = {}, total = 0 }
    end

    local items = {}
    local total = 0
    local idx = 0

    -- Try as Java iterable
    local ok, iter = pcall(java.iter, obj)
    if ok then
      for item in iter do
        if idx >= ${start} and idx < ${start + limit} then
          if item == nil then
            table.insert(items, { isNull = true, index = idx })
          else
            local desc = java.describe(item)
            desc.index = idx
            table.insert(items, desc)
          end
        end
        idx = idx + 1
        total = total + 1
      end
    else
      -- Try as array with length
      local len = obj.length or obj.size and obj:size() or 0
      total = len
      for i = ${start}, math.min(${start + limit - 1}, len - 1) do
        local item = obj[i]
        if item == nil then
          table.insert(items, { isNull = true, index = i })
        else
          local desc = java.describe(item)
          desc.index = i
          table.insert(items, desc)
        end
      end
    end

    return { items = items, total = total }
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to get collection');
  }

  const data = result.result as { items: unknown[], total: number };
  return {
    items: data.items.map(item => parseObjectInfo(unwrapBridgeValue(item))),
    total: data.total
  };
}

/**
 * The mod's JSON serializer wraps every value in {type, value}.
 * Recursively unwrap these envelopes to get the plain data.
 */
function unwrapBridgeValue(data: unknown): unknown {
  if (data === null || data === undefined) return data;
  if (typeof data !== 'object' || Array.isArray(data)) return data;

  const obj = data as Record<string, unknown>;

  // Detect the {type, value} envelope: has exactly "type" and "value" keys
  // where "type" is a string like "table", "string", "number", "boolean", "nil"
  if ('type' in obj && 'value' in obj && typeof obj.type === 'string') {
    const t = obj.type as string;
    if (t === 'table') {
      return unwrapBridgeValue(obj.value);
    }
    if (t === 'string' || t === 'number' || t === 'boolean' || t === 'nil' || t === 'userdata') {
      return obj.value;
    }
  }

  // Recursively unwrap object properties
  const result: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(obj)) {
    result[key] = unwrapBridgeValue(val);
  }
  return result;
}

function parseObjectInfo(data: unknown, defaultClassName?: string): ObjectInfo {
  if (!data || typeof data !== 'object') {
    return {
      className: defaultClassName || 'unknown',
      shortName: defaultClassName?.split('.').pop() || 'unknown',
      fields: [],
      methods: [],
      isNull: true
    };
  }

  const obj = data as Record<string, unknown>;

  if (obj.isNull) {
    return {
      className: (obj.className as string) || defaultClassName || 'null',
      shortName: 'null',
      fields: [],
      methods: [],
      isNull: true
    };
  }

  const rawClass = obj.__class ?? obj.className ?? defaultClassName ?? 'Object';
  const className = typeof rawClass === 'string' ? rawClass : String(rawClass);
  const shortName = className.split('.').pop() || className;

  const fields: FieldInfo[] = [];
  const methods: string[] = [];

  // Parse fields from java.describe() output
  if (obj.fields && typeof obj.fields === 'object') {
    for (const [name, info] of Object.entries(obj.fields as Record<string, unknown>)) {
      const fieldData = info as Record<string, unknown>;
      fields.push({
        name,
        type: fieldData.type != null ? String(fieldData.type) : 'unknown',
        value: fieldData.value,
        valueType: determineValueType(fieldData.value),
        className: fieldData.valueClass != null ? String(fieldData.valueClass) : undefined,
        expandable: isExpandable(fieldData.value),
        modifiers: fieldData.modifiers != null ? String(fieldData.modifiers) : undefined
      });
    }
  }

  // If no structured fields, treat all non-__ properties as fields
  if (fields.length === 0) {
    for (const [key, value] of Object.entries(obj)) {
      if (key.startsWith('__')) continue;
      if (key === 'isNull' || key === 'className') continue;

      fields.push({
        name: key,
        type: typeof value,
        value,
        valueType: determineValueType(value),
        expandable: isExpandable(value)
      });
    }
  }

  // Parse methods
  if (obj.methods && Array.isArray(obj.methods)) {
    methods.push(...(obj.methods as string[]));
  }

  return {
    className,
    shortName,
    fields,
    methods,
    superClass: obj.superClass != null ? String(obj.superClass) : undefined,
    interfaces: obj.interfaces as string[],
    isNull: false,
    displayValue: obj.__toString != null ? String(obj.__toString) : undefined
  };
}

function determineValueType(value: unknown): FieldInfo['valueType'] {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'string') return 'string';
  if (typeof value === 'number' || typeof value === 'boolean') return 'primitive';
  if (Array.isArray(value)) return 'array';
  return 'object';
}

function isExpandable(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === 'object') return true;
  return false;
}
