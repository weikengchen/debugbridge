<script setup lang="ts">
import { ref } from 'vue'
import BrowserPanel from './BrowserPanel.vue'
import LuaInspector from './LuaInspector.vue'
import InventoryPanel from './InventoryPanel.vue'
import EntitiesPanel from './EntitiesPanel.vue'

type Mode = 'browser' | 'lua' | 'inventory' | 'entities'
const mode = ref<Mode>('browser')

const tabs: { id: Mode; label: string }[] = [
  { id: 'inventory', label: '🎒 Inventory' },
  { id: 'entities', label: '👁 Entities' },
  { id: 'browser', label: '🌳 Object Browser' },
  { id: 'lua', label: '📝 Lua Inspector' },
]
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Mode toggle -->
    <div class="flex border-b border-zinc-800">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        @click="mode = tab.id"
        class="px-4 py-2 text-sm transition-colors"
        :class="mode === tab.id
          ? 'text-zinc-100 border-b-2 border-green-500'
          : 'text-zinc-400 hover:text-zinc-200'"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- Content -->
    <div class="flex-1 overflow-hidden">
      <InventoryPanel v-if="mode === 'inventory'" />
      <EntitiesPanel v-else-if="mode === 'entities'" />
      <BrowserPanel v-else-if="mode === 'browser'" />
      <LuaInspector v-else />
    </div>
  </div>
</template>
