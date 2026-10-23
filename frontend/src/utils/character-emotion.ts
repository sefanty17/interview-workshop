/**
 * 情绪名 ↔ 立绘文件名的映射（照搬 references/lingchat/src/controllers/emotion/config.ts 的 EMOTION_CONFIG_EMO）。
 * 展示用的是「哭泣 / 难为情」这类说法，而 /avatars 目录下实际的文件是「伤心 / 羞耻」，
 * 所以 key 是展示名、value 是拼 URL 用的文件名（data/interviewers.ts 的 avatarUrl 认的是后者）。
 */
export const EMOTION_CONFIG_EMO: Record<string, string> = {
  厌恶: "厌恶",
  高兴: "高兴",
  担心: "担心",
  生气: "生气",
  紧张: "紧张",
  害怕: "害怕",
  害羞: "害羞",
  慌张: "慌张",
  认真: "认真",
  无奈: "无奈",
  兴奋: "兴奋",
  疑惑: "疑惑",
  哭泣: "伤心",
  心动: "心动",
  调皮: "调皮",
  难为情: "羞耻",
  自信: "自信",
  惊讶: "惊讶",
  正常: "正常",
  平静: "平静",
};

/** 差分管理 / 布局 Preview 的情绪下拉选项（原版直接取 EMOTION_CONFIG_EMO 的键） */
export const EMOTION_NAMES = Object.keys(EMOTION_CONFIG_EMO);
