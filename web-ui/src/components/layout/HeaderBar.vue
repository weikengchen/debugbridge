<script setup lang="ts">
import { ref } from 'vue'
import { useConnectionStore } from '../../stores/connection'

const connection = useConnectionStore()
const portInput = ref('')
const showPortInput = ref(false)

async function handleConnect() {
  if (connection.isConnected) {
    connection.disconnect()
  } else {
    const port = portInput.value ? parseInt(portInput.value) : undefined
    try {
      await connection.connect(port)
      showPortInput.value = false
      portInput.value = ''
    } catch {
      // Error is stored in connection.error
    }
  }
}

function togglePortInput() {
  showPortInput.value = !showPortInput.value
}
</script>

<template>
  <header class="h-12 bg-zinc-900 border-b border-zinc-800 flex items-center px-4 gap-4">
    <!-- Logo/Title -->
    <div class="flex items-center gap-2">
      <div class="w-6 h-6 bg-green-500 rounded flex items-center justify-center text-xs font-bold text-black">
        DB
      </div>
      <span class="font-semibold text-zinc-100">DebugBridge</span>
    </div>

    <!-- Connection status -->
    <div class="flex items-center gap-2 ml-auto">
      <!-- Session info -->
      <div v-if="connection.sessionInfo" class="text-xs text-zinc-400">
        MC {{ connection.sessionInfo.version }}
        <span class="text-zinc-600">|</span>
        Port {{ connection.port }}
      </div>

      <!-- Port input -->
      <div v-if="showPortInput && !connection.isConnected" class="flex items-center gap-1">
        <input
          v-model="portInput"
          type="number"
          placeholder="9876"
          class="w-20 px-2 py-1 text-xs bg-zinc-800 border border-zinc-700 rounded focus:outline-none focus:border-zinc-500"
          @keyup.enter="handleConnect"
        />
      </div>

      <!-- Port toggle button -->
      <button
        v-if="!connection.isConnected"
        @click="togglePortInput"
        class="text-xs text-zinc-500 hover:text-zinc-300"
        title="Specify port"
      >
        ⚙
      </button>

      <!-- Status dot -->
      <div
        class="status-dot"
        :class="{
          'status-connected': connection.isConnected,
          'status-connecting': connection.isConnecting,
          'status-disconnected': !connection.isConnected && !connection.isConnecting
        }"
      ></div>

      <!-- Connect/Disconnect button -->
      <button
        @click="handleConnect"
        :disabled="connection.isConnecting"
        class="btn text-xs"
        :class="connection.isConnected ? 'btn-secondary' : 'btn-primary'"
      >
        {{ connection.isConnecting ? 'Connecting...' : connection.isConnected ? 'Disconnect' : 'Connect' }}
      </button>
    </div>

    <!-- Error display -->
    <div v-if="connection.error" class="text-xs text-red-400 max-w-xs truncate" :title="connection.error">
      {{ connection.error }}
    </div>
  </header>
</template>
