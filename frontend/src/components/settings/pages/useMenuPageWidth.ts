// 原版 MenuPage.vue 的容器宽度算法（等价实现：uiStore.viewportWidth/Height → window.innerWidth/Height）
import { computed, onMounted, onUnmounted, ref } from "vue";

export function useMenuPageWidth() {
  const windowWidth = ref(window.innerWidth);
  const windowHeight = ref(window.innerHeight);

  const updateWidth = () => {
    windowWidth.value = window.innerWidth;
    windowHeight.value = window.innerHeight;
  };

  onMounted(() => window.addEventListener("resize", updateWidth));
  onUnmounted(() => window.removeEventListener("resize", updateWidth));

  const containerWidth = computed(() => {
    const ratio = windowWidth.value / windowHeight.value;
    const currentWidth = windowWidth.value;

    // 1. 维度一：基于屏幕比例 (原逻辑)
    // 窄屏适配：ratio 1.0→0.5，宽度 70%→100%
    let ratioWidth = 70;
    if (ratio < 1.0) {
      ratioWidth = 70 + (1.0 - ratio) * 60;
    }

    // 2. 维度二：基于绝对分辨率 (新逻辑)
    let resolutionWidth = 70;
    if (currentWidth < 1024) {
      const scale = (1024 - currentWidth) / (1024 - 675);
      resolutionWidth = 70 + scale * 30;
    }

    // 3. 综合判断：取两者中需要更宽的那一个，并限制最高不超过 100%
    const finalPercent = Math.max(ratioWidth, resolutionWidth);

    return Math.round(Math.min(100, finalPercent));
  });

  return { containerWidth };
}
