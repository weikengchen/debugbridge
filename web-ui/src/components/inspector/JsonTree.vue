<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  value: unknown
  keyName?: string
  depth: number
  root?: boolean
}>()

const expanded = ref((props.depth ?? 0) < 1)

const kind = computed<'object' | 'array' | 'string' | 'number' | 'boolean' | 'null'>(() => {
  const v = props.value
  if (v === null || v === undefined) return 'null'
  if (Array.isArray(v)) return 'array'
  if (typeof v === 'object') return 'object'
  if (typeof v === 'string') return 'string'
  if (typeof v === 'number') return 'number'
  if (typeof v === 'boolean') return 'boolean'
  return 'string'
})

const entries = computed<[string, unknown][]>(() => {
  if (kind.value === 'object') {
    return Object.entries(props.value as Record<string, unknown>)
  }
  if (kind.value === 'array') {
    return (props.value as unknown[]).map((v, i) => [String(i), v])
  }
  return []
})

const isExpandable = computed(() =>
  (kind.value === 'object' || kind.value === 'array') && entries.value.length > 0
)

const summary = computed(() => {
  if (kind.value === 'object') {
    const n = entries.value.length
    return `{${n} ${n === 1 ? 'field' : 'fields'}}`
  }
  if (kind.value === 'array') {
    const n = entries.value.length
    return `[${n} ${n === 1 ? 'item' : 'items'}]`
  }
  return ''
})

function toggle() {
  if (isExpandable.value) expanded.value = !expanded.value
}
</script>

<template>
  <div class="font-mono text-xs leading-snug">
    <!-- Root (no key) renders the container directly -->
    <template v-if="root">
      <template v-for="([k, v]) in entries" :key="k">
        <JsonTree :value="v" :key-name="k" :depth="depth + 1" />
      </template>
    </template>

    <!-- Non-root node -->
    <template v-else>
      <div
        class="flex items-start gap-1 hover:bg-zinc-800/40 rounded cursor-default"
        :style="{ paddingLeft: `${depth * 12}px` }"
        @click.stop="toggle"
      >
        <button
          v-if="isExpandable"
          class="json-toggle flex-shrink-0"
        >{{ expanded ? '▼' : '▶' }}</button>
        <span v-else class="w-3 flex-shrink-0"></span>

        <span class="json-key flex-shrink-0">{{ keyName }}</span>
        <span class="text-zinc-600 flex-shrink-0">:</span>

        <span v-if="kind === 'string'" class="json-string break-all">"{{ value }}"</span>
        <span v-else-if="kind === 'number'" class="json-number">{{ value }}</span>
        <span v-else-if="kind === 'boolean'" class="json-boolean">{{ value }}</span>
        <span v-else-if="kind === 'null'" class="json-null">null</span>
        <span v-else class="json-summary">{{ summary }}</span>
      </div>

      <div v-if="isExpandable && expanded">
        <JsonTree
          v-for="([k, v]) in entries"
          :key="k"
          :value="v"
          :key-name="k"
          :depth="depth + 1"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.json-toggle {
  color: #71717a;
  width: 12px;
  font-size: 10px;
}
.json-toggle:hover {
  color: #a1a1aa;
}
.json-key {
  color: #93c5fd;
}
.json-string {
  color: #86efac;
}
.json-number {
  color: #fbbf24;
}
.json-boolean {
  color: #c084fc;
}
.json-null {
  color: #71717a;
  font-style: italic;
}
.json-summary {
  color: #a1a1aa;
}
</style>
