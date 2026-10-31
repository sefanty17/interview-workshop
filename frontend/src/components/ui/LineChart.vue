<template>
  <div ref="el" class="chart-box" :style="{ height: height + 'px' }" />
</template>

<script setup lang="ts">
import * as echarts from "echarts";
import { onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{
    labels: string[];
    /** 单线数据；与 `series` 同时存在时以 `series` 为准 */
    values?: number[];
    /** 多线数据（成长曲线按维度分线）；为空时退回单线 `values` */
    series?: { name: string; values: number[]; color?: string }[];
    height?: number;
  }>(),
  { height: 220, values: () => [], series: () => [] },
);

/** 多线默认配色：顺序与后端四维度固定顺序 technical / depth / logic / fit 对应（首条是总分）。 */
const SERIES_COLORS = ["#79d9ff", "#ffd166", "#7ee787", "#a78bfa", "#ff8fa3"];

const el = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;

function render() {
  if (!chart) return;
  const multi = props.series.length > 0;
  const values = multi ? props.series.flatMap((s) => s.values) : props.values;
  const data = values.length ? values : [0];
  let min = Math.max(0, Math.min(...data) - 10);
  let max = Math.min(100, Math.max(...data) + 10);
  if (min === max) {
    min = Math.max(0, min - 5);
    max = Math.min(100, max + 5);
  }

  const series = multi
    ? props.series.map((item, index) => {
        const color = item.color ?? SERIES_COLORS[index % SERIES_COLORS.length];
        return {
          name: item.name,
          type: "line",
          smooth: true,
          data: item.values,
          symbol: "circle",
          symbolSize: 5,
          lineStyle: { color, width: 2 },
          itemStyle: { color },
        };
      })
    : [
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
      ];

  chart.setOption(
    {
      backgroundColor: "transparent",
      grid: { left: 34, right: 16, top: multi ? 34 : 20, bottom: 26 },
      tooltip: { trigger: "axis" },
      legend: multi
        ? {
            top: 0,
            right: 0,
            itemWidth: 12,
            itemHeight: 8,
            textStyle: { color: "#8299b8", fontSize: 10 },
          }
        : undefined,
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
      series,
    },
    true,
  );
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
  () => [props.labels, props.values, props.series],
  () => render(),
  { deep: true },
);

onBeforeUnmount(() => {
  window.removeEventListener("resize", resize);
  chart?.dispose();
  chart = null;
});
</script>
