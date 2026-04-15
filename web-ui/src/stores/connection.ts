import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { bridge } from '../services/bridge';
import type { SessionInfo, ConnectionStatus } from '../types';

export const useConnectionStore = defineStore('connection', () => {
  const status = ref<ConnectionStatus>('disconnected');
  const sessionInfo = ref<SessionInfo | null>(null);
  const port = ref<number | null>(null);
  const error = ref<string | null>(null);

  const isConnected = computed(() => status.value === 'connected');
  const isConnecting = computed(() => status.value === 'connecting');

  // Listen to bridge connection changes
  bridge.onConnectionChange((newStatus, info) => {
    status.value = newStatus;
    if (info) {
      sessionInfo.value = info;
    }
    if (newStatus === 'connected') {
      port.value = bridge.port;
      error.value = null;
    } else if (newStatus === 'disconnected') {
      sessionInfo.value = null;
    }
  });

  async function connect(portNumber?: number) {
    error.value = null;
    try {
      await bridge.connect(portNumber);
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      throw e;
    }
  }

  function disconnect() {
    bridge.disconnect();
    port.value = null;
  }

  return {
    status,
    sessionInfo,
    port,
    error,
    isConnected,
    isConnecting,
    connect,
    disconnect,
  };
});
