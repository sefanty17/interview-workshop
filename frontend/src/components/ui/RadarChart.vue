<template>
  <div ref="el" class="radar-box" :style="{ height: height + 'px' }" />
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
  { height: 300 },
);

const el = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;

function render() {
  if (!chart) return;
  chart.setOption({
    backgroundColor: "transparent",
    tooltip: {},
    radar: {
      center: ["50%", "52%"],
      radius: "68%",
      splitNumber: 4,
      axisName: {
        color: "#b9cde6",
        fontSize: 11,
        fontFamily: "Microsoft YaHei, sans-serif",
      },
      indicator: props.labels.map((name) => ({ name, max: 100 })),
      splitLine: { lineStyle: { color: "rgba(121,217,255,0.12)" } },
      splitArea: {
        areaStyle: {
          color: ["rgba(121,217,255,0.02)", "rgba(121,217,255,0.05)"],
        },
      },
      axisLine: { lineStyle: { color: "rgba(121,217,255,0.18)" } },
    },
    series: [
      {
        type: "radar",
        symbol: "circle",
        symbolSize: 5,
        lineStyle: { color: "#79d9ff", width: 2, shadowColor: "rgba(121,217,255,.55)", shadowBlur: 12 },
        itemStyle: { color: "#79d9ff", borderColor: "#04101f", borderWidth: 1 },
        areaStyle: {
          color: new echarts.graphic.RadialGradient(0.5, 0.5, 0.8, [
            { offset: 0, color: "rgba(121,217,255,0.42)" },
            { offset: 1, color: "rgba(91,140,255,0.08)" },
          ]),
        },
        data: [{ value: props.values, name: "能力维度" }],
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
