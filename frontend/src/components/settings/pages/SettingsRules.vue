<template>
  <MenuPage>
    <!-- ===== 评分与评估口径 ===== -->
    <MenuItem title="评分与评估口径" size="large">
      <template #header>
        <ClipboardCheck :size="20" class="text-cyan-300" />
      </template>

      <p class="mb-4 text-xs leading-relaxed text-white/45">
        报告页的总分由后端调用大模型评估得出，四个维度与权重写死在
        <code class="rounded bg-white/10 px-1">EvaluationDimension</code>
        里，不提供自定义维度。
      </p>

      <!-- 四维权重 -->
      <div class="mb-4 grid grid-cols-1 gap-2 sm:grid-cols-2">
        <div
          v-for="dim in DIMENSIONS"
          :key="dim.code"
          class="flex items-center justify-between rounded-lg border border-white/10 bg-white/5 px-3 py-2"
        >
          <span class="text-sm text-white/80">{{ dim.label }}</span>
          <span class="text-xs text-cyan-300 tabular-nums">{{ (dim.weight * 100).toFixed(0) }}%</span>
        </div>
      </div>

      <ul class="m-0 list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-white/70">
        <li>总分是 0-100 的整数；四个维度权重合计 100%。</li>
        <li>
          <span class="text-white">只有剧本场次进趋势与评估</span>：自由对话只用于对话体验，不产生评分。
        </li>
        <li>
          本场作答不足 <span class="text-white">3 轮</span>
          时视为无效面试：总分记 0，报告页不带评估结果（URL 上会标
          <code class="rounded bg-white/10 px-1">noeval=1</code>）。
        </li>
        <li>后端评估本身要求至少 2 次求职者回答，更少时直接报「对话不足」。</li>
        <li>重跑评估是覆盖式：同一场只保留最新一份评估，不会追加。</li>
      </ul>
    </MenuItem>

    <!-- ===== 媒体降级规则 ===== -->
    <MenuItem title="媒体降级规则" size="large">
      <template #header>
        <AudioLines :size="20" class="text-cyan-300" />
      </template>

      <p class="mb-3 text-xs leading-relaxed text-white/45">
        剧本可以下发 6 类舞台媒体事件，任一资源缺失都不阻断剧本推进——降级只影响「看得见 / 听得到」的部分。
      </p>

      <div class="overflow-hidden rounded-xl border border-white/5 bg-white/[0.03]">
        <table class="w-full text-left text-sm">
          <thead class="bg-white/5 text-xs text-white/50">
            <tr>
              <th class="px-3 py-2 font-medium">事件</th>
              <th class="px-3 py-2 font-medium">缺资源时的表现</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-white/5 text-white/70">
            <tr v-for="row in MEDIA_RULES" :key="row.event">
              <td class="px-3 py-2 whitespace-nowrap text-white/80">{{ row.event }}</td>
              <td class="px-3 py-2 text-xs leading-relaxed">{{ row.fallback }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <ul class="mt-4 mb-0 list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-white/70">
        <li>
          背景 / 背景特效 / BGM / 环境音 / 展示图片会随事件写入场次媒体快照，刷新页面后自动恢复；
          <span class="text-white">音效是一次性播放，只记录不恢复</span>（重放会凭空多一声）。
        </li>
        <li>
          当前
          <code class="rounded bg-white/10 px-1">public</code>
          里只有音效目录（audio_effects），没有 BGM 与环境音素材，
          因此声效面板里出现「资源缺失」灰条属于预期行为。
        </li>
        <li>本机没有真实音频采集：「音频频谱」目前是演示正弦波动画，不代表实际音量。</li>
      </ul>
    </MenuItem>

    <!-- ===== 记录与报告 ===== -->
    <MenuItem title="记录与报告" size="large">
      <template #header>
        <FileText :size="20" class="text-cyan-300" />
      </template>

      <ul class="m-0 list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-white/70">
        <li>面试记录由后端统一提供，本地不再另存一份，避免同一场出现两条。</li>
        <li>报告页地址一律带 <code class="rounded bg-white/10 px-1">?sid=</code>，没有场次号只会落到本地占位报告。</li>
        <li>刷新面试房间会续回同一个后端场次（章节位置与舞台媒体状态都保留），不会新建一场。</li>
      </ul>
    </MenuItem>
  </MenuPage>
</template>

<script setup lang="ts">
import { AudioLines, ClipboardCheck, FileText } from "@lucide/vue";
import { MenuItem, MenuPage } from "../../ui";

/** 与后端 EvaluationDimension 保持同一口径；改权重必须两边一起改。 */
const DIMENSIONS = [
  { code: "technical", label: "技术正确性", weight: 0.3 },
  { code: "depth", label: "知识深度", weight: 0.25 },
  { code: "logic", label: "逻辑严谨性", weight: 0.25 },
  { code: "fit", label: "岗位匹配度", weight: 0.2 },
];

/** 6 类剧本媒体事件的降级表现（与 roomAudio / roomStore 的实际行为一致）。 */
const MEDIA_RULES = [
  { event: "背景（background）", fallback: "图片路径 404 时背景层是空白图，台词推进不受影响" },
  { event: "背景特效（background-effect）", fallback: "特效名不在可用清单内时不渲染粒子层，只留背景" },
  { event: "BGM（music）", fallback: "音频 404 时静默跳过，声效面板显示「资源缺失」灰条" },
  { event: "音效（sound）", fallback: "一次性播放，缺失时静默忽略；刷新后不会重放" },
  { event: "环境音（ambient）", fallback: "按路径逐轨降级，缺一轨不影响其余音轨" },
  { event: "展示图片（present-pic）", fallback: "图片缺失时浮层是空白图，不影响台词与选项" },
];
</script>
