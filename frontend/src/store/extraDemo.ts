/**
 * 房间附加层演示数据（B7）：
 * 章节名 / Galgame 选项 / 剧本完成庆祝 / 自由对话轮数 / 立绘演示图。
 * 默认全部关闭：只有剧本模式下有对应数据、或控制台 `window.__extraDemo(true)`
 * 打开演示模式时才渲染（见 components/game/GameExtraUI.vue）。
 */
import { reactive } from "vue";
import { seedScripts } from "../data/scripts";

export interface FreeDialogueInfo {
  isFreeDialogue: boolean;
  maxRounds: number;
  endLine: string;
  currentRound: number;
}

const firstScript = seedScripts[0];

/** Galgame 选项演示数据（4 个选项） */
export const DEMO_CHOICES = [
  "先做一分钟自我介绍",
  "直接讲最熟悉的项目",
  "反问岗位业务方向",
  "承认这块准备不足",
];

export const extraDemo = reactive({
  /** 演示总开关：默认关闭；控制台 `window.__extraDemo(true)` 才挂载这批演示层 */
  demoEnabled: false,
  /** Galgame 选项层 */
  showChoices: false,
  choices: [...DEMO_CHOICES] as string[],
  /** 剧本完成庆祝层 */
  showComplete: false,
  scriptName: firstScript?.script_name ?? "Java 后端 · 严格三轮",
  /** 章节名层 */
  showChapter: false,
  chapterName: firstScript?.chapters[0]?.name ?? "破冰与自我介绍",
  /** 自由对话剩余轮数（默认关闭，进入自由对话 / 演示时才显示） */
  freeDialogue: {
    isFreeDialogue: false,
    maxRounds: 6,
    endLine: "今天的面试就到这里吧",
    currentRound: 3,
  } as FreeDialogueInfo,
  /** 立绘演示图（空串 = 不显示） */
  presentPic: "",
  /** 演示 BGM 状态（声效面板与迷你频谱共用；无真实音频） */
  bgm: {
    current: "",
    paused: false,
    stoped: true,
    mode: "loop-single" as "loop-list" | "loop-single" | "random",
  },
});

/**
 * 演示模式开关：`window.__extraDemo(true)` 打开，`false` 关闭。
 * 各层数据在附加层挂载后再置位，子组件内部 watch 才能收到 false → true。
 */
export function setExtraDemo(on = true): boolean {
  extraDemo.demoEnabled = on;
  if (on) {
    window.setTimeout(() => {
      if (!extraDemo.demoEnabled) return;
      extraDemo.showChoices = true;
      extraDemo.showChapter = true;
      extraDemo.showComplete = true;
      extraDemo.freeDialogue.isFreeDialogue = true;
      extraDemo.presentPic = "/avatars/strict/正常.webp";
    }, 50);
  } else {
    extraDemo.showChoices = false;
    extraDemo.showChapter = false;
    extraDemo.showComplete = false;
    extraDemo.freeDialogue.isFreeDialogue = false;
    extraDemo.presentPic = "";
  }
  return extraDemo.demoEnabled;
}

if (typeof window !== "undefined") {
  const w = window as unknown as {
    __extraDemo?: (on?: boolean) => boolean;
    __extraDemoState?: typeof extraDemo;
  };
  w.__extraDemo = setExtraDemo;
  w.__extraDemoState = extraDemo;
}
