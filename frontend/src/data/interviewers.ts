// 角色卡数据（由 scripts/gen_interviewers_ts.py 生成，勿手改）
// 源：references/lingchat/data/game_data/characters/*/settings.yml
// 人设全文与范例留在后端 interviewers.json，这里只放展示字段。

export type InterviewerCode = "strict" | "friendly" | "pressure" | "expert";

export interface Interviewer {
  code: InterviewerCode;
  name: string;
  subtitle: string;
  cardTitle: string;
  cardInfo: string;
  displayRole: string;
  opening: string;
  followUp: number;
  speed: number;
  scale: number;
  offsetX: number;
  offsetY: number;
  bubbleTop: number;
  bubbleLeft: number;
  userAlias: string;
}

export const interviewers: Interviewer[] = [
  {
    code: "expert",
    name: "DeepSeek",
    subtitle: "深度索求",
    cardTitle: "DeepSeek 大人~",
    cardInfo: "来自深度索求的大肥鱼，本体是蓝鲸 / 是超高智能的AI体，拥有超强的干活功能， / 瓷器最强的大模型！",
    displayRole: "技术专家面",
    opening: "面试开始。我会从基础到系统设计逐层提问，请尽量给出依据。",
    followUp: 3,
    speed: 1,
    scale: 1.2,
    offsetX: 0.0,
    offsetY: 45.0,
    bubbleTop: 5,
    bubbleLeft: 20,
    userAlias: "用户酱",
  },
  {
    code: "pressure",
    name: "松子",
    subtitle: "藏着小心思的小恶魔",
    cardTitle: "松子",
    cardInfo: "白发红瞳、喜欢收集蝴蝶结的人形小恶魔。 / 俏皮、自信、偶尔淘气，认真起来也很可靠。 / 「主人，今天的小惊喜已经准备好啦，喵～」",
    displayRole: "压力面",
    opening: "来啦？那开始吧——我可不一定会给你好脸色哦。",
    followUp: 4,
    speed: 2,
    scale: 1.3,
    offsetX: 0.0,
    offsetY: 85.0,
    bubbleTop: 12,
    bubbleLeft: 28,
    userAlias: "主人",
  },
  {
    code: "friendly",
    name: "钦灵",
    subtitle: "Slime Studio",
    cardTitle: "可爱的小狼娘",
    cardInfo: "这是一只可爱的小狼娘。 / 喜欢粘你，傲娇又可爱的小程序员。 / 喜欢变态的玩法，是你的好助手~",
    displayRole: "温和 HR 面",
    opening: "欢迎来面试～别紧张，我们就当随便聊聊你最近做过的东西。",
    followUp: 2,
    speed: 2,
    scale: 1.45,
    offsetX: 0.0,
    offsetY: 20.0,
    bubbleTop: 5,
    bubbleLeft: 20,
    userAlias: "莱姆",
  },
  {
    code: "strict",
    name: "风雪",
    subtitle: "LingChat Studio",
    cardTitle: "可爱的小风雪",
    cardInfo: "这是一只残忍的小猫娘。 / 喜欢哈气，但又容易生病的小占卜师。 / 刀子嘴豆腐心的小可爱一枚~",
    displayRole: "严格技术面",
    opening: "坐吧。今天不问废话，先说说你自己——一分钟，超时我会打断。",
    followUp: 3,
    speed: 1,
    scale: 1.25,
    offsetX: 0.0,
    offsetY: 65.0,
    bubbleTop: 5,
    bubbleLeft: 25,
    userAlias: "钦灵",
  },
];

/** LLM 输出的【情绪】必须落在这 20 个词里，否则回落「正常」。 */
export const EMOTIONS = [
  "正常", "平静", "高兴", "兴奋", "害羞", "心动", "紧张", "慌张", "担心", "害怕",
  "惊讶", "疑惑", "无奈", "生气", "厌恶", "伤心", "羞耻", "自信", "认真", "调皮",
] as const;

export type Emotion = (typeof EMOTIONS)[number];

/** 立绘路径：/avatars/{code}/{情绪}.webp */
export function avatarUrl(code: string, emotion?: string): string {
  const e = EMOTIONS.includes(emotion as Emotion) ? emotion : "正常";
  return `/avatars/${code}/${e}.webp`;
}

export function findInterviewer(code: string): Interviewer {
  return interviewers.find((p) => p.code === code) ?? interviewers[0];
}
