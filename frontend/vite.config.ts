import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    // 前端只调 /api，跨域交给代理（后端另有 CORS 白名单，直连 8080 也能用）
    proxy: { "/api": { target: "http://127.0.0.1:8080", changeOrigin: true } },
  },
  preview: { host: "127.0.0.1", port: 4173 },
});
