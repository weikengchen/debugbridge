<script setup lang="ts">
import { computed } from 'vue'
import { useInspectorStore, type ObjectNode } from '../../stores/inspector'

const props = defineProps<{
  node: ObjectNode
  depth: number
}>()

const inspector = useInspectorStore()

const indent = computed(() => props.depth * 16)

const valueClass = computed(() => {
  const value = props.node.value
  if (value === null) return 'object-value-null'
  if (typeof value === 'string') return 'object-value-string'
  if (typeof value === 'number') return 'object-value-number'
  if (typeof value === 'boolean') return 'object-value-boolean'
  return ''
})

const displayValue = computed(() => {
  const value = props.node.value
  if (value === null) return 'null'
  if (value === undefined) return 'undefined'

  if (typeof value === 'string') {
    const truncated = value.length > 100 ? value.slice(0, 100) + '...' : value
    return `"${truncated}"`
  }

  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }

  if (Array.isArray(value)) {
    return `Array[${value.length}]`
  }

  if (typeof value === 'object') {
    const obj = value as Record<string, unknown>
    // Java object with class info
    if (obj.__class) {
      const className = obj.__class as string
      const shortName = className.split('.').pop() || className
      if (obj.__toString) {
        return `${shortName}: ${obj.__toString}`
      }
      return shortName
    }
    return `{${Object.keys(obj).filter(k => !k.startsWith('__')).length} keys}`
  }

  return String(value)
})

function toggleExpand() {
  if (!props.node.expandable) return

  if (props.node.expanded) {
    props.node.expanded = false
  } else {
    inspector.expandNode(props.node)
    props.node.expanded = true
  }
}

function copyPath() {
  navigator.clipboard.writeText(props.node.path)
}

function copyValue() {
  const value = typeof props.node.value === 'object'
    ? JSON.stringify(props.node.value, null, 2)
    : String(props.node.value)
  navigator.clipboard.writeText(value)
}
</script>

<template>
  <div class="font-mono text-sm">
    <!-- Node row -->
    <div
      class="flex items-center gap-1 py-0.5 hover:bg-zinc-800/50 rounded group"
      :style="{ paddingLeft: `${indent}px` }"
    >
      <!-- Expand toggle -->
      <button
        v-if="node.expandable"
        @click="toggleExpand"
        class="tree-toggle"
      >
        <span v-if="node.loading" class="animate-spin">↻</span>
        <span v-else>{{ node.expanded ? '▼' : '▶' }}</span>
      </button>
      <span v-else class="w-4"></span>

      <!-- Key -->
      <span class="object-key">{{ node.key }}</span>
      <span class="text-zinc-600">:</span>

      <!-- Type badge -->
      <span v-if="node.type && node.type !== 'string' && node.type !== 'number' && node.type !== 'boolean'" class="object-type ml-1">
        {{ node.type }}
      </span>

      <!-- Value -->
      <span :class="valueClass" class="ml-1 truncate max-w-md" :title="String(displayValue)">
        {{ displayValue }}
      </span>

      <!-- Actions (shown on hover) -->
      <div class="ml-auto opacity-0 group-hover:opacity-100 flex gap-1">
        <button @click="copyPath" class="text-xs text-zinc-500 hover:text-zinc-300" title="Copy path">
          📋
        </button>
        <button @click="copyValue" class="text-xs text-zinc-500 hover:text-zinc-300" title="Copy value">
          📄
        </button>
      </div>
    </div>

    <!-- Children -->
    <div v-if="node.expanded && node.children">
      <ObjectTree
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
      />
    </div>
  </div>
</template>
