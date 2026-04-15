import { defineStore } from 'pinia';
import { ref } from 'vue';
import { bridge } from '../services/bridge';
import type { PinnedObject } from '../types';

export interface ObjectNode {
  id: string;
  key: string;
  value: unknown;
  type: string;
  expandable: boolean;
  expanded: boolean;
  children?: ObjectNode[];
  loading?: boolean;
  refId?: string;
  path: string;
}

export const useInspectorStore = defineStore('inspector', () => {
  const rootObject = ref<ObjectNode | null>(null);
  const pinnedObjects = ref<PinnedObject[]>([]);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const selectedPath = ref<string | null>(null);

  // Load pinned objects from localStorage
  const savedPinned = localStorage.getItem('debugbridge-pinned');
  if (savedPinned) {
    try {
      pinnedObjects.value = JSON.parse(savedPinned);
    } catch {
      // Ignore
    }
  }

  function savePinned() {
    localStorage.setItem('debugbridge-pinned', JSON.stringify(pinnedObjects.value));
  }

  function generateNodeId(): string {
    return `node_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  function determineType(value: unknown): { type: string; expandable: boolean } {
    if (value === null) return { type: 'null', expandable: false };
    if (value === undefined) return { type: 'undefined', expandable: false };

    const t = typeof value;
    if (t === 'string') return { type: 'string', expandable: false };
    if (t === 'number') return { type: 'number', expandable: false };
    if (t === 'boolean') return { type: 'boolean', expandable: false };
    if (t === 'function') return { type: 'function', expandable: false };

    if (Array.isArray(value)) {
      return { type: `array[${value.length}]`, expandable: value.length > 0 };
    }

    if (t === 'object') {
      const obj = value as Record<string, unknown>;
      // Check if it's a Java object reference
      if (obj.__class) {
        return { type: obj.__class as string, expandable: true };
      }
      if (obj.__javaClass) {
        return { type: obj.__javaClass as string, expandable: true };
      }
      const keys = Object.keys(obj);
      return { type: 'object', expandable: keys.length > 0 };
    }

    return { type: t, expandable: false };
  }

  function createNode(key: string, value: unknown, path: string): ObjectNode {
    const { type, expandable } = determineType(value);
    const node: ObjectNode = {
      id: generateNodeId(),
      key,
      value,
      type,
      expandable,
      expanded: false,
      path,
    };

    // Extract refId if it's a Java object
    if (typeof value === 'object' && value !== null) {
      const obj = value as Record<string, unknown>;
      if (obj.__refId) {
        node.refId = obj.__refId as string;
      }
    }

    return node;
  }

  function expandNode(node: ObjectNode): ObjectNode[] {
    if (!node.expandable || node.children) return node.children || [];

    const value = node.value;
    const children: ObjectNode[] = [];

    if (Array.isArray(value)) {
      value.forEach((item, index) => {
        children.push(createNode(`[${index}]`, item, `${node.path}[${index}]`));
      });
    } else if (typeof value === 'object' && value !== null) {
      const obj = value as Record<string, unknown>;
      for (const [key, val] of Object.entries(obj)) {
        // Skip internal properties
        if (key.startsWith('__')) continue;
        children.push(createNode(key, val, `${node.path}.${key}`));
      }
    }

    node.children = children;
    return children;
  }

  async function inspect(code: string, name?: string): Promise<void> {
    isLoading.value = true;
    error.value = null;

    try {
      // Wrap code to return a detailed inspection
      const inspectCode = `
        local obj = (function()
          ${code}
        end)()
        if obj == nil then
          return { __null = true }
        end
        return obj
      `;

      const result = await bridge.execute(inspectCode);

      if (!result.success) {
        throw new Error(result.error || 'Inspection failed');
      }

      rootObject.value = createNode(name || 'result', result.result, 'root');
      selectedPath.value = 'root';
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      rootObject.value = null;
    } finally {
      isLoading.value = false;
    }
  }

  async function inspectDeep(node: ObjectNode): Promise<void> {
    if (!node.refId) return;

    node.loading = true;

    try {
      const code = `
        local ref = java.ref("${node.refId}")
        if ref == nil then return { __null = true } end
        return java.describe(ref)
      `;

      const result = await bridge.execute(code);

      if (result.success && result.result) {
        // Replace node's value with detailed inspection
        node.value = result.result;
        node.children = undefined; // Force re-expansion
        expandNode(node);
      }
    } finally {
      node.loading = false;
    }
  }

  function pinObject(name: string, code: string, refId?: string): void {
    const existing = pinnedObjects.value.find(p => p.name === name);
    if (existing) {
      // Update existing pin
      existing.code = code;
      existing.refId = refId || existing.refId;
    } else {
      pinnedObjects.value.push({
        id: generateNodeId(),
        name,
        code,
        refId: refId || '',
        className: '',
        pinnedAt: new Date(),
      });
    }
    savePinned();
  }

  function unpinObject(id: string): void {
    const idx = pinnedObjects.value.findIndex(p => p.id === id);
    if (idx >= 0) {
      pinnedObjects.value.splice(idx, 1);
      savePinned();
    }
  }

  function clear(): void {
    rootObject.value = null;
    error.value = null;
    selectedPath.value = null;
  }

  return {
    rootObject,
    pinnedObjects,
    isLoading,
    error,
    selectedPath,
    inspect,
    inspectDeep,
    expandNode,
    pinObject,
    unpinObject,
    clear,
  };
});
