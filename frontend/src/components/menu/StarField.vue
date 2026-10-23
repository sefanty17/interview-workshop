<template>
  <div class="star-field" aria-hidden="true">
    <span
      v-for="star in stars"
      :key="star.id"
      class="star"
      :style="{
        left: star.left + '%',
        top: star.top + '%',
        width: star.size + 'px',
        height: star.size + 'px',
        animationDelay: star.delay + 's',
        opacity: star.opacity,
      }"
    />
  </div>
</template>

<script setup lang="ts">
const stars = Array.from({ length: 42 }, (_, i) => {
  const seed = (i * 9301 + 49297) % 233280;
  const rnd = seed / 233280;
  const rnd2 = ((i * 4177 + 12345) % 233280) / 233280;
  return {
    id: i,
    left: Number((rnd * 100).toFixed(2)),
    top: Number((rnd2 * 100).toFixed(2)),
    size: Number((1 + (i % 3) * 0.7).toFixed(1)),
    delay: Number((i % 9) * 0.45),
    opacity: Number((0.25 + (i % 5) * 0.12).toFixed(2)),
  };
});
</script>

<style scoped>
.star-field {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  z-index: 1;
}
</style>
