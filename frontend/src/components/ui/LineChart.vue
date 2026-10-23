<template>
  <div ref="el" class="chart-box" :style="{ height: height + 'px' }" />
</template>

<script setup lang="ts">
import * as echarts from "echarts";
import { onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{
    labels: string[];
    values: number[];
    height?: number;
  }>(),
  { height: 220 },
);

const el = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;

function render() {
  if (!chart) return;
  const values = props.values.length ? props.values : [0];
  let min = Math.max(0, Math.min(...values) - 10);
  let max = Math.min(100, Math.max(...values) + 10);
  if (min === max) {
    min = Math.max(0, min - 5);
    max = Math.min(100, max + 5);
  }
  chart.setOption({
    backgroundColor: "transparent",
    grid: { left: 34, right: 16, top: 20, bottom: 26 },
    tooltip: { trigger: "axis" },
    xAxis: {
      type: "category",
      data: props.labels,
      axisLine: { lineStyle: { color: "rgba(121,217,255,.18)" } },
      axisLabel: { color: "#8299b8", fontSize: 10 },
      axisTick: { show: false },
    },
    yAxis: {
      type: "value",
      min,
      max,
      splitLine: { lineStyle: { color: "rgba(121,217,255,.08)" } },
      axisLabel: { color: "#5a6f8d", fontSize: 10 },
    },
    series: [
      {
        type: "line",
        smooth: true,
        data: props.values,
        symbol: "circle",
        symbolSize: 7,
        lineStyle: { color: "#79d9ff", width: 2 },
        itemStyle: { color: "#79d9ff", borderColor: "#04101f", borderWidth: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: "rgba(121,217,255,0.28)" },
            { offset: 1, color: "rgba(121,217,255,0)" },
          ]),
        },
      },
    ],
  });
}

function resize() {
  chart?.resize();
}

onMounted(() => {
  if (!el.value) return;
  chart = echarts.init(el.value);
  render();
  window.addEventListener("resize", resize);
});

watch(
  () => [props.labels, props.values],
  () => render(),
  { deep: true },
);

onBeforeUnmount(() => {
  window.removeEventListener("resize", resize);
  chart?.dispose();
  chart = null;
});
</script>
