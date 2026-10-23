import { createApp } from "vue";
import App from "./App.vue";
import router from "./router";
import "./styles/tokens.css";
import "./styles/base.css";
import "./styles/menu.css";
import "./styles/console.css";
import "./styles/views.css";
import "./styles/lingchat.css";
import "./styles/lingchat-globals.css";
// 立绘情绪动画与气泡样式（全局注入，全项目只准有这一份呼吸关键帧定义）
import "./assets/styles/avatar-animation.css";

createApp(App).use(router).mount("#app");
