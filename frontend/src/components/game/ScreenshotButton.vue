<template>
  <!-- 截图按钮：视觉同原版（nav 型 + 相机图标）。html2canvas 截图链路本批不接，
       点击由父组件提示「待接入」 -->
  <Button type="nav" icon="camera" title="截图（待接入）" @click="emit('start')"></Button>
</template>

<script setup lang="ts">
import { Button } from "../base";

const emit = defineEmits<{ start: [] }>();
</script>
