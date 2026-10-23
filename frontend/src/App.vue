<template>
  <router-view v-slot="{ Component }">
    <transition name="page" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>

  <AppDialog />

  <transition name="fade">
    <div v-if="store.toast" class="toast">{{ store.toast }}</div>
  </transition>
</template>

<script setup lang="ts">
import AppDialog from "./components/ui/AppDialog.vue";
import { store } from "./store/appStore";
</script>

<style>
.toast {
  position: fixed;
  left: 50%;
  bottom: 34px;
  z-index: 999;
  transform: translateX(-50%);
  padding: 10px 18px;
  border-radius: 999px;
  border: 1px solid rgba(121, 217, 255, 0.32);
  background: rgba(8, 20, 38, 0.94);
  color: var(--text);
  font-size: 12.5px;
  letter-spacing: 0.03em;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(10px);
}
</style>
