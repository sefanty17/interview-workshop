<template>
  <!-- 原版 components/ui/ImageAcrossFade.vue 的同名等价实现：
       两层图片 + 一次淡入。这里只做静态取景（contain / auto NN%），
       并把 overlay 插槽放在图片之上，供触摸区域编辑器挂 SVG 叠层 -->
  <div class="relative h-full w-full">
    <slot></slot>
    <slot name="overlay"></slot>
    <div
      class="pointer-events-none absolute inset-0 overflow-hidden"
      :style="{ opacity: shown ? 1 : 0, transition: `opacity ${duration}ms ease-in-out` }"
    >
      <img
        :src="src"
        alt=""
        draggable="false"
        class="pointer-events-none"
        :style="imageStyle"
        @load="shown = true"
        @error="shown = false"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{ src: string; duration?: number; objectFit?: string; position?: string }>(),
  { duration: 300, objectFit: "contain", position: "center bottom" },
);

const shown = ref(false);

watch(
  () => props.src,
  (url) => {
    shown.value = false;
    if (!url) return;
    // 命中缓存时 img 可能早于 watch 完成加载，这里补一次判定
    const image = new Image();
    image.src = url;
    if (image.complete && image.naturalWidth) shown.value = true;
  },
  { immediate: true },
);

const imageStyle = computed(() => {
  const height = /^auto (\d+(?:\.\d+)?)%$/.exec(props.objectFit)?.[1];
  if (height) {
    const [x = "center", y = "center"] = props.position.split(/\s+/);
    const anchors: Record<string, string> = {
      left: "0%",
      right: "100%",
      top: "0%",
      bottom: "100%",
      center: "50%",
    };
    const left = anchors[x] ?? x;
    const top = anchors[y] ?? y;
    return {
      width: "auto",
      height,
      maxWidth: "none",
      maxHeight: "none",
      left,
      top,
      transform: `translate(calc(-1 * ${left}), calc(-1 * ${top}))`,
    };
  }
  return {
    width: "100%",
    height: "100%",
    maxWidth: "none",
    maxHeight: "none",
    objectFit: props.objectFit as "contain" | "cover",
    objectPosition: props.position,
  };
});
</script>
