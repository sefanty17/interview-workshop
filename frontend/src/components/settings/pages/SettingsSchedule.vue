<template>
  <MenuPage>
    <ScheduleContent />
  </MenuPage>
</template>

<script setup lang="ts">
/**
 * 设置页形态的日程（原版 SettingsNav 里没有「日程」入口，SettingsSchedule 只作为
 * 组件映射存在；房间左上角的 SchedulePanel 才是常用入口）。
 * 内容区 = ScheduleContent（日程 / 待办 / 日历 / 主动对话 / 工具调用五个子页）。
 */
import { MenuPage } from "../../ui";
import ScheduleContent from "../../schedule/ScheduleContent.vue";
</script>
