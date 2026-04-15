<script setup lang="ts">
import { useConnectionStore } from '../../stores/connection'
import { useInspectorStore } from '../../stores/inspector'

const props = defineProps<{
  activeTab: 'console' | 'inspector' | 'dashboard'
}>()

const emit = defineEmits<{
  'update:activeTab': [value: 'console' | 'inspector' | 'dashboard']
}>()

const connection = useConnectionStore()
const inspector = useInspectorStore()

const tabs = [
  { id: 'console' as const, label: 'Console', icon: '>' },
  { id: 'inspector' as const, label: 'Inspector', icon: '{}' },
  { id: 'dashboard' as const, label: 'Dashboard', icon: '◉' },
]

function selectTab(id: typeof props.activeTab) {
  emit('update:activeTab', id)
}

async function inspectPinned(pinned: typeof inspector.pinnedObjects[0]) {
  emit('update:activeTab', 'inspector')
  await inspector.inspect(pinned.code, pinned.name)
}

async function quickInspect(target: 'minecraft' | 'player' | 'world') {
  emit('update:activeTab', 'inspector')
  const code = {
    minecraft: 'return java.import("net.minecraft.client.Minecraft"):getInstance()',
    player: 'return java.import("net.minecraft.client.Minecraft"):getInstance().player',
    world: 'return java.import("net.minecraft.client.Minecraft"):getInstance().level',
  }
  const name = {
    minecraft: 'Minecraft',
    player: 'Player',
    world: 'World',
  }
  await inspector.inspect(code[target], name[target])
}
</script>

<template>
  <aside class="w-48 bg-zinc-900/50 border-r border-zinc-800 flex flex-col">
    <!-- Navigation tabs -->
    <nav class="p-2 space-y-1">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        @click="selectTab(tab.id)"
        class="w-full px-3 py-2 rounded-md text-left text-sm flex items-center gap-2 transition-colors"
        :class="activeTab === tab.id
          ? 'bg-zinc-700 text-zinc-100'
          : 'text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800'"
      >
        <span class="w-5 text-center font-mono text-xs">{{ tab.icon }}</span>
        {{ tab.label }}
      </button>
    </nav>

    <!-- Pinned objects -->
    <div v-if="inspector.pinnedObjects.length > 0" class="flex-1 overflow-auto border-t border-zinc-800 mt-2">
      <div class="p-2">
        <div class="text-xs text-zinc-500 uppercase tracking-wide mb-2">Pinned</div>
        <div class="space-y-1">
          <button
            v-for="pinned in inspector.pinnedObjects"
            :key="pinned.id"
            @click="inspectPinned(pinned)"
            class="w-full px-2 py-1.5 rounded text-left text-xs text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800 truncate flex items-center gap-2 group"
          >
            <span class="text-purple-400">◆</span>
            <span class="flex-1 truncate">{{ pinned.name }}</span>
            <button
              @click.stop="inspector.unpinObject(pinned.id)"
              class="opacity-0 group-hover:opacity-100 text-zinc-500 hover:text-red-400"
              title="Unpin"
            >
              ×
            </button>
          </button>
        </div>
      </div>
    </div>

    <!-- Quick actions -->
    <div class="p-2 border-t border-zinc-800">
      <div class="text-xs text-zinc-500 uppercase tracking-wide mb-2">Quick Actions</div>
      <div class="space-y-1">
        <button
          @click="quickInspect('minecraft')"
          :disabled="!connection.isConnected"
          class="w-full px-2 py-1.5 rounded text-left text-xs text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          → Minecraft Client
        </button>
        <button
          @click="quickInspect('player')"
          :disabled="!connection.isConnected"
          class="w-full px-2 py-1.5 rounded text-left text-xs text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          → Player
        </button>
        <button
          @click="quickInspect('world')"
          :disabled="!connection.isConnected"
          class="w-full px-2 py-1.5 rounded text-left text-xs text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          → World
        </button>
      </div>
    </div>
  </aside>
</template>
