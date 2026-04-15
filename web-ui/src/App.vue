<script setup lang="ts">
import { ref } from 'vue'
import HeaderBar from './components/layout/HeaderBar.vue'
import Sidebar from './components/layout/Sidebar.vue'
import ConsolePanel from './components/console/ConsolePanel.vue'
import InspectorPanel from './components/inspector/InspectorPanel.vue'
import DashboardPanel from './components/dashboard/DashboardPanel.vue'

type Tab = 'console' | 'inspector' | 'dashboard'
const activeTab = ref<Tab>('console')
</script>

<template>
  <div class="h-screen flex flex-col bg-[#0f0f17] text-zinc-200">
    <!-- Header -->
    <HeaderBar />

    <!-- Main content -->
    <div class="flex-1 flex overflow-hidden">
      <!-- Sidebar -->
      <Sidebar v-model:active-tab="activeTab" />

      <!-- Main panel area -->
      <main class="flex-1 flex flex-col overflow-hidden">
        <!-- Tab content -->
        <div class="flex-1 overflow-hidden">
          <ConsolePanel v-if="activeTab === 'console'" />
          <InspectorPanel v-else-if="activeTab === 'inspector'" />
          <DashboardPanel v-else-if="activeTab === 'dashboard'" />
        </div>
      </main>
    </div>
  </div>
</template>
