import { defineStore } from 'pinia';
import { ref } from 'vue';
import { bridge } from '../services/bridge';
import type { ConsoleEntry } from '../types';

export const useConsoleStore = defineStore('console', () => {
  const entries = ref<ConsoleEntry[]>([]);
  const history = ref<string[]>([]);
  const historyIndex = ref(-1);
  const isExecuting = ref(false);
  const currentCode = ref('');

  function generateId(): string {
    return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }

  function addEntry(type: ConsoleEntry['type'], content: string, extra?: Partial<ConsoleEntry>) {
    entries.value.push({
      id: generateId(),
      timestamp: new Date(),
      type,
      content,
      ...extra,
    });

    // Keep last 500 entries
    if (entries.value.length > 500) {
      entries.value = entries.value.slice(-500);
    }
  }

  async function execute(code: string): Promise<void> {
    if (!code.trim() || isExecuting.value) return;

    // Add to history
    if (history.value[history.value.length - 1] !== code) {
      history.value.push(code);
      if (history.value.length > 100) {
        history.value = history.value.slice(-100);
      }
    }
    historyIndex.value = -1;

    // Log input
    addEntry('input', code, { code });

    isExecuting.value = true;
    const startTime = Date.now();

    try {
      const result = await bridge.execute(code);
      const duration = Date.now() - startTime;

      if (result.output) {
        addEntry('output', result.output);
      }

      if (result.success) {
        if (result.result !== undefined) {
          const resultStr = typeof result.result === 'string'
            ? result.result
            : JSON.stringify(result.result, null, 2);
          addEntry('result', resultStr, { duration });
        } else if (!result.output) {
          addEntry('result', '(no output)', { duration });
        }
      } else {
        addEntry('error', result.error || 'Unknown error', { duration });
      }
    } catch (e) {
      const duration = Date.now() - startTime;
      const errorMsg = e instanceof Error ? e.message : String(e);
      addEntry('error', errorMsg, { duration });
    } finally {
      isExecuting.value = false;
    }
  }

  function navigateHistory(direction: 'up' | 'down'): string | null {
    if (history.value.length === 0) return null;

    if (direction === 'up') {
      if (historyIndex.value < history.value.length - 1) {
        historyIndex.value++;
      }
    } else {
      if (historyIndex.value > 0) {
        historyIndex.value--;
      } else if (historyIndex.value === 0) {
        historyIndex.value = -1;
        return '';
      }
    }

    if (historyIndex.value >= 0) {
      return history.value[history.value.length - 1 - historyIndex.value];
    }
    return null;
  }

  function clear() {
    entries.value = [];
  }

  return {
    entries,
    history,
    isExecuting,
    currentCode,
    execute,
    navigateHistory,
    clear,
    addEntry,
  };
});
