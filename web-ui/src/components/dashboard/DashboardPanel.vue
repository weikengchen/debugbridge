<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useConnectionStore } from '../../stores/connection'
import { bridge } from '../../services/bridge'

const connection = useConnectionStore()
const snapshot = ref<any>(null)
const screenshot = ref<string | null>(null)
const screenshotInfo = ref<{ width: number; height: number } | null>(null)
const isLoadingSnapshot = ref(false)
const isLoadingScreenshot = ref(false)
const error = ref<string | null>(null)
const autoRefresh = ref(false)
let refreshInterval: ReturnType<typeof setInterval> | null = null

async function loadSnapshot() {
  if (!connection.isConnected) return

  isLoadingSnapshot.value = true
  error.value = null

  try {
    snapshot.value = await bridge.snapshot()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    isLoadingSnapshot.value = false
  }
}

async function takeScreenshot() {
  if (!connection.isConnected) return

  isLoadingScreenshot.value = true

  try {
    const result = await bridge.screenshot(2, 0.75)
    // For now, we can't display the screenshot directly since it's on the server
    // In a full implementation, we'd need an HTTP endpoint to serve the image
    screenshotInfo.value = { width: result.width, height: result.height }
    screenshot.value = result.path
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    isLoadingScreenshot.value = false
  }
}

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value

  if (autoRefresh.value) {
    loadSnapshot()
    refreshInterval = setInterval(loadSnapshot, 1000)
  } else if (refreshInterval) {
    clearInterval(refreshInterval)
    refreshInterval = null
  }
}

onMounted(() => {
  if (connection.isConnected) {
    loadSnapshot()
  }
})

onUnmounted(() => {
  if (refreshInterval) {
    clearInterval(refreshInterval)
  }
})
</script>

<template>
  <div class="h-full overflow-auto p-4">
    <div v-if="!connection.isConnected" class="text-zinc-500 text-center py-8">
      Connect to Minecraft to view the dashboard
    </div>

    <div v-else class="space-y-4">
      <!-- Controls -->
      <div class="flex gap-2 items-center">
        <button @click="loadSnapshot" :disabled="isLoadingSnapshot" class="btn btn-primary">
          {{ isLoadingSnapshot ? 'Loading...' : 'Refresh' }}
        </button>
        <button
          @click="toggleAutoRefresh"
          :class="autoRefresh ? 'btn-danger' : 'btn-secondary'"
          class="btn"
        >
          {{ autoRefresh ? 'Stop Auto-Refresh' : 'Auto-Refresh' }}
        </button>
        <button @click="takeScreenshot" :disabled="isLoadingScreenshot" class="btn btn-secondary">
          {{ isLoadingScreenshot ? 'Capturing...' : '📷 Screenshot' }}
        </button>
      </div>

      <div v-if="error" class="text-red-400 text-sm">{{ error }}</div>

      <!-- Screenshot info -->
      <div v-if="screenshot" class="panel">
        <div class="panel-header">
          <span class="panel-title">Screenshot</span>
        </div>
        <div class="panel-body text-sm">
          <p>Saved to: <code class="text-xs bg-zinc-800 px-1 rounded">{{ screenshot }}</code></p>
          <p class="text-zinc-400" v-if="screenshotInfo">{{ screenshotInfo.width }}×{{ screenshotInfo.height }}</p>
        </div>
      </div>

      <div v-if="snapshot" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <!-- Player Stats -->
        <div v-if="snapshot.player" class="panel">
          <div class="panel-header">
            <span class="panel-title">Player</span>
          </div>
          <div class="panel-body space-y-2 text-sm">
            <div class="flex justify-between">
              <span class="text-zinc-400">Health</span>
              <span>
                <span class="text-red-400">❤</span>
                {{ snapshot.player.health?.toFixed(1) }} / {{ snapshot.player.maxHealth }}
              </span>
            </div>
            <div class="flex justify-between">
              <span class="text-zinc-400">Food</span>
              <span>
                <span class="text-yellow-400">🍖</span>
                {{ snapshot.player.food }} ({{ snapshot.player.saturation?.toFixed(1) }})
              </span>
            </div>
            <div class="flex justify-between">
              <span class="text-zinc-400">XP Level</span>
              <span class="text-green-400">{{ snapshot.player.experienceLevel }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-zinc-400">Game Mode</span>
              <span>{{ snapshot.player.gameMode }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-zinc-400">Dimension</span>
              <span class="text-purple-400">{{ snapshot.player.dimension }}</span>
            </div>
          </div>
        </div>

        <!-- Position -->
        <div v-if="snapshot.player?.position" class="panel">
          <div class="panel-header">
            <span class="panel-title">Position</span>
          </div>
          <div class="panel-body space-y-2 text-sm font-mono">
            <div class="flex justify-between">
              <span class="text-red-400">X</span>
              <span>{{ snapshot.player.position.x?.toFixed(2) }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-green-400">Y</span>
              <span>{{ snapshot.player.position.y?.toFixed(2) }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-blue-400">Z</span>
              <span>{{ snapshot.player.position.z?.toFixed(2) }}</span>
            </div>
          </div>
        </div>

        <!-- World -->
        <div v-if="snapshot.world" class="panel">
          <div class="panel-header">
            <span class="panel-title">World</span>
          </div>
          <div class="panel-body space-y-2 text-sm">
            <div class="flex justify-between">
              <span class="text-zinc-400">Time</span>
              <span>{{ snapshot.world.dayTime }} ticks</span>
            </div>
            <div class="flex justify-between">
              <span class="text-zinc-400">Weather</span>
              <span>{{ snapshot.world.weather || 'Clear' }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-zinc-400">Difficulty</span>
              <span>{{ snapshot.world.difficulty }}</span>
            </div>
          </div>
        </div>

        <!-- Performance -->
        <div v-if="snapshot.performance" class="panel">
          <div class="panel-header">
            <span class="panel-title">Performance</span>
          </div>
          <div class="panel-body space-y-2 text-sm">
            <div class="flex justify-between">
              <span class="text-zinc-400">FPS</span>
              <span :class="snapshot.performance.fps > 30 ? 'text-green-400' : 'text-yellow-400'">
                {{ snapshot.performance.fps }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- Raw JSON -->
      <details v-if="snapshot" class="panel">
        <summary class="panel-header cursor-pointer">
          <span class="panel-title">Raw Data</span>
        </summary>
        <div class="panel-body">
          <pre class="text-xs overflow-auto max-h-64 text-zinc-400">{{ JSON.stringify(snapshot, null, 2) }}</pre>
        </div>
      </details>
    </div>
  </div>
</template>
