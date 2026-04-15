<script setup lang="ts">
import { ref } from 'vue'
import { useInspectorStore } from '../../stores/inspector'
import { useConnectionStore } from '../../stores/connection'
import ObjectTree from './ObjectTree.vue'

const inspector = useInspectorStore()
const connection = useConnectionStore()
const codeInput = ref('')

async function handleInspect() {
  if (!codeInput.value.trim()) return
  await inspector.inspect(codeInput.value)
}

function handlePin() {
  if (!inspector.rootObject) return
  const name = prompt('Enter a name for this pin:', inspector.rootObject.key)
  if (name) {
    inspector.pinObject(name, codeInput.value, inspector.rootObject.refId)
  }
}
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Input area -->
    <div class="p-4 border-b border-zinc-800">
      <div class="flex gap-2">
        <input
          v-model="codeInput"
          :disabled="!connection.isConnected"
          placeholder="Enter Lua expression to inspect (e.g., return mc.player)"
          class="flex-1 px-3 py-2 bg-zinc-800 border border-zinc-700 rounded-md font-mono text-sm text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-zinc-500 disabled:opacity-50"
          @keyup.enter="handleInspect"
        />
        <button
          @click="handleInspect"
          :disabled="!connection.isConnected || inspector.isLoading || !codeInput.trim()"
          class="btn btn-primary"
        >
          {{ inspector.isLoading ? 'Loading...' : 'Inspect' }}
        </button>
        <button
          v-if="inspector.rootObject"
          @click="handlePin"
          class="btn btn-secondary"
          title="Pin this object"
        >
          📌
        </button>
        <button
          v-if="inspector.rootObject"
          @click="inspector.clear"
          class="btn btn-secondary"
          title="Clear"
        >
          ✕
        </button>
      </div>

      <div v-if="!connection.isConnected" class="text-xs text-zinc-500 mt-2">
        Connect to Minecraft to use the inspector
      </div>
    </div>

    <!-- Object tree -->
    <div class="flex-1 overflow-auto p-4">
      <div v-if="inspector.error" class="text-red-400 mb-4">
        {{ inspector.error }}
      </div>

      <div v-if="inspector.rootObject">
        <ObjectTree :node="inspector.rootObject" :depth="0" />
      </div>

      <div v-else-if="!inspector.isLoading" class="text-zinc-500 text-center py-8">
        <p>Enter a Lua expression to inspect an object.</p>
        <p class="text-xs mt-2">
          Convenience globals: <code class="text-blue-300">mc</code>,
          <code class="text-blue-300">player</code>,
          <code class="text-blue-300">level</code> resolve to the current Minecraft
          instance, local player, and client level.
        </p>
        <p class="text-xs mt-2">Examples:</p>
        <ul class="text-xs mt-1 space-y-1">
          <li><code class="text-blue-300">return mc</code></li>
          <li><code class="text-blue-300">return player:blockPosition()</code></li>
          <li><code class="text-blue-300">return level:entitiesForRendering()</code></li>
          <li><code class="text-blue-300">return java.import("net.minecraft.client.Minecraft")</code></li>
        </ul>
      </div>
    </div>
  </div>
</template>
