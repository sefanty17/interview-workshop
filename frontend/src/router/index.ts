import { createRouter, createWebHistory } from "vue-router";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "home", component: () => import("../views/HomeView.vue") },
    { path: "/interview", name: "interview", component: () => import("../views/InterviewRoomView.vue") },
    { path: "/settings", name: "settings", component: () => import("../views/SettingsView.vue") },
    { path: "/script-editor", name: "script-editor", component: () => import("../views/ScriptEditorView.vue") },
    { path: "/workshop", name: "workshop", component: () => import("../views/WorkshopView.vue") },
    { path: "/records", name: "records", component: () => import("../views/RecordsView.vue") },
    { path: "/report/:id", name: "report", component: () => import("../views/ReportView.vue") },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});

export default router;
