<template>
  <div class="console">
    <aside class="sidebar">
      <router-link to="/" class="sidebar-brand">
        <span class="brand-mark" />
        <span class="brand-text">
          <strong>面试工坊</strong>
          <span>INTERVIEW LAB</span>
        </span>
      </router-link>

      <div class="side-label">WORKSPACE</div>
      <nav class="side-nav">
        <router-link to="/interview" class="nav-link">
          <span class="ico">01</span><span>面试房间</span>
        </router-link>
        <router-link to="/records" class="nav-link">
          <span class="ico">02</span><span>面试记录</span>
        </router-link>
        <router-link to="/settings" class="nav-link">
          <span class="ico">03</span><span>面试设置</span>
        </router-link>
        <router-link to="/script-editor" class="nav-link">
          <span class="ico">04</span><span>剧本编辑</span>
        </router-link>
        <router-link to="/workshop" class="nav-link">
          <span class="ico">05</span><span>创意工坊</span>
        </router-link>
      </nav>

      <div class="sidebar-foot">
        <div class="user-chip">
          <div class="user-avatar">蔡</div>
          <div>
            <strong>蔡同学</strong>
            <span>DEMO · CANDIDATE</span>
          </div>
        </div>
      </div>
    </aside>

    <main class="console-main">
      <slot />
    </main>
  </div>
</template>

<script setup lang="ts">
defineProps<{ title?: string }>();
</script>
