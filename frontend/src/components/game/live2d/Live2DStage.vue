<template>
  <div
    v-bind="$attrs"
    ref="host"
    class="pointer-events-none absolute inset-0 overflow-hidden"
    aria-hidden="true"
  >
    <!-- Live2D 渲染需 Cubism SDK，S3 后端阶段接入；当前回落静态立绘 -->
    <div class="absolute inset-0">
      <slot></slot>
    </div>

    <!-- 抚摸粒子放在画布容器内部：它要盖住模型却压在气泡之下，而 PIXI 的画布是
         运行时才追加到这个 div 末尾的，只能靠 z-1 压过它那个 auto -->
    <TouchParticles ref="touchParticles" />
  </div>
</template>

<script setup lang="ts">
import { provide, ref } from "vue";
import TouchParticles from "./TouchParticles.vue";
import { live2dStageContextKey, type Live2dStageContext } from "./live2d-stage-context";

/**
 * Live2D 舞台外壳（照搬原版外层容器结构与层级约定）。
 *
 * 原版在此挂载 PIXI + Cubism 运行时渲染模型、处理视线/动作/口型同步；
 * Cubism SDK 依赖未引入（不新增 npm 依赖），S3 后端阶段接入。
 * 当前保留：
 * - 外层容器：absolute inset-0 + overflow hidden + pointer-events none，与原版一致；
 * - TouchParticles：抚摸时的粒子反馈，纯 canvas 实现，已照搬可用；
 * - stage context：readyRoleIds 恒为空，Live2DRolePresentation 据此自动回落静态立绘。
 */
const host = ref<HTMLElement | null>(null);
const touchParticles = ref<InstanceType<typeof TouchParticles> | null>(null);

const emptyIds = ref<ReadonlySet<number>>(new Set());

provide(live2dStageContextKey, {
  readyRoleIds: emptyIds,
  unavailableRoleIds: emptyIds,
} as Live2dStageContext);
</script>
