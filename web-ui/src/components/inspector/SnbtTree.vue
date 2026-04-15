<script setup lang="ts">
import { ref, computed } from 'vue'

export interface SnbtNode {
  type: 'compound' | 'list' | 'string' | 'number' | 'boolean' | 'raw'
  key?: string
  value?: string          // leaf value
  children?: SnbtNode[]   // compound/list children
}

const props = defineProps<{
  node: SnbtNode
  depth?: number
}>()

const depth = computed(() => props.depth ?? 0)
const expanded = ref(depth.value < 1) // auto-expand first level

const indent = computed(() => depth.value * 16)

const isExpandable = computed(() =>
  (props.node.type === 'compound' || props.node.type === 'list') &&
  props.node.children && props.node.children.length > 0
)

const displayValue = computed(() => {
  const n = props.node
  const prefix = (n.type === 'compound' || n.type === 'list') && n.value ? n.value + ' ' : ''
  if (n.type === 'compound') {
    const count = n.children?.length ?? 0
    return `${prefix}{${count} ${count === 1 ? 'entry' : 'entries'}}`
  }
  if (n.type === 'list') {
    const count = n.children?.length ?? 0
    return `${prefix}[${count} ${count === 1 ? 'item' : 'items'}]`
  }
  return n.value ?? ''
})

const valueClass = computed(() => {
  switch (props.node.type) {
    case 'string': return 'snbt-string'
    case 'number': return 'snbt-number'
    case 'boolean': return 'snbt-boolean'
    case 'compound':
    case 'list': return 'snbt-structure'
    default: return 'snbt-raw'
  }
})

function toggle() {
  if (isExpandable.value) expanded.value = !expanded.value
}

function copyValue() {
  const text = props.node.value ?? JSON.stringify(props.node, null, 2)
  navigator.clipboard.writeText(text)
}
</script>

<template>
  <div class="font-mono text-xs">
    <div
      class="flex items-start gap-1 py-px hover:bg-zinc-800/50 rounded group leading-snug"
      :style="{ paddingLeft: `${indent}px` }"
    >
      <!-- Expand toggle -->
      <button
        v-if="isExpandable"
        @click="toggle"
        class="snbt-toggle flex-shrink-0"
      >
        {{ expanded ? '▼' : '▶' }}
      </button>
      <span v-else class="w-3.5 flex-shrink-0"></span>

      <!-- Key -->
      <span v-if="node.key !== undefined" class="snbt-key flex-shrink-0">{{ node.key }}</span>
      <span v-if="node.key !== undefined" class="text-zinc-600 flex-shrink-0">: </span>

      <!-- Value -->
      <span :class="valueClass" class="break-all" :title="displayValue">
        {{ displayValue }}
      </span>

      <!-- Copy button on hover -->
      <button
        @click.stop="copyValue"
        class="ml-auto opacity-0 group-hover:opacity-100 text-zinc-500 hover:text-zinc-300 flex-shrink-0 px-1"
        title="Copy value"
      >
        copy
      </button>
    </div>

    <!-- Children -->
    <div v-if="isExpandable && expanded">
      <SnbtTree
        v-for="(child, i) in node.children"
        :key="child.key ?? i"
        :node="child"
        :depth="depth + 1"
      />
    </div>
  </div>
</template>

<style scoped>
.snbt-toggle {
  width: 14px;
  color: #888;
  cursor: pointer;
  font-size: 9px;
  text-align: center;
  line-height: 1.4;
  user-select: none;
}
.snbt-toggle:hover {
  color: #ccc;
}
.snbt-key {
  color: #7dd3fc; /* sky-300 */
}
.snbt-string {
  color: #86efac; /* green-300 */
}
.snbt-number {
  color: #fdba74; /* orange-300 */
}
.snbt-boolean {
  color: #c084fc; /* purple-400 */
}
.snbt-structure {
  color: #71717a; /* zinc-500 */
}
.snbt-raw {
  color: #a1a1aa; /* zinc-400 */
}
</style>
