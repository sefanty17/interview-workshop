/**
 * 情绪演出配置（照搬 references/lingchat/src/controllers/emotion/config.ts）。
 *
 * 与原版唯一的差异：`bubbleImage` / `audio` 原版指向
 * `../pictures/animation/*.webp`、`../audio_effects/*.wav`（Tauri 本地资源），
 * 本项目把这两类素材放进 `public/`（规格 E7a1 §4），路径改成 web 根路径
 * `/pictures/animation/*.webp`、`/audio_effects/*.wav`；
 * 原版本来就是 `"none"` 的（认真 / 平静 / 正常）保持 `"none"`。
 * `animation` 字段逐字照搬。
 *
 * EMOTION_CONFIG_EMO 已在 src/utils/character-emotion.ts 落过一份，
 * 这里直接转出，避免同一份情绪→文件名映射出现两份副本各自漂移。
 */
interface EmotionConfig {
  animation: string;
  bubbleImage: string;
  bubbleClass: string;
  audio: string;
}

interface EmotionConfigMap {
  [key: string]: EmotionConfig;
}

export { EMOTION_CONFIG_EMO } from "../../utils/character-emotion";

export const EMOTION_CONFIG: EmotionConfigMap = {
  厌恶: {
    animation: "none",
    bubbleImage: "/pictures/animation/生气.webp",
    bubbleClass: "angry",
    audio: "/audio_effects/厌恶.wav",
  },
  高兴: {
    animation: "happy-bounce",
    bubbleImage: "/pictures/animation/高兴.webp",
    bubbleClass: "happy",
    audio: "/audio_effects/喜悦.wav",
  },
  担心: {
    animation: "none",
    bubbleImage: "/pictures/animation/流泪.webp",
    bubbleClass: "none",
    audio: "/audio_effects/伤心.wav",
  },
  生气: {
    animation: "angry-jump",
    bubbleImage: "/pictures/animation/生气2.webp",
    bubbleClass: "angry",
    audio: "/audio_effects/生气.wav",
  },
  紧张: {
    animation: "none",
    bubbleImage: "/pictures/animation/紧张.webp",
    bubbleClass: "none",
    audio: "/audio_effects/尴尬.wav",
  },
  害怕: {
    animation: "none",
    bubbleImage: "/pictures/animation/惊讶.webp",
    bubbleClass: "none",
    audio: "/audio_effects/震惊.wav",
  },
  害羞: {
    animation: "none",
    bubbleImage: "/pictures/animation/害羞.webp",
    bubbleClass: "shy",
    audio: "/audio_effects/害羞.wav",
  },
  慌张: {
    animation: "none",
    bubbleImage: "/pictures/animation/慌乱.webp",
    bubbleClass: "none",
    audio: "/audio_effects/震惊.wav",
  },
  认真: {
    animation: "serious-think",
    bubbleImage: "none",
    bubbleClass: "none",
    audio: "none",
  },
  无奈: {
    animation: "none",
    bubbleImage: "/pictures/animation/叹气.webp",
    bubbleClass: "none",
    audio: "/audio_effects/叹气.wav",
  },
  兴奋: {
    animation: "happy-bounce",
    bubbleImage: "/pictures/animation/聊天.webp",
    bubbleClass: "none",
    audio: "/audio_effects/聊天.wav",
  },
  疑惑: {
    animation: "none",
    bubbleImage: "/pictures/animation/疑问.webp",
    bubbleClass: "none",
    audio: "/audio_effects/疑问.wav",
  },
  哭泣: {
    animation: "none",
    bubbleImage: "/pictures/animation/流泪.webp",
    bubbleClass: "none",
    audio: "/audio_effects/伤心.wav",
  },
  心动: {
    animation: "heart-beat",
    bubbleImage: "/pictures/animation/心动.webp",
    bubbleClass: "none",
    audio: "/audio_effects/喜爱.wav",
  },
  调皮: {
    animation: "naughty-bounce",
    bubbleImage: "/pictures/animation/高兴.webp",
    bubbleClass: "none",
    audio: "/audio_effects/愉快.wav",
  },
  难为情: {
    animation: "embarrassed-emo",
    bubbleImage: "/pictures/animation/难为情.webp",
    bubbleClass: "none",
    audio: "/audio_effects/察觉.wav",
  },
  自信: {
    animation: "none",
    bubbleImage: "/pictures/animation/高兴.webp",
    bubbleClass: "none",
    audio: "/audio_effects/愉快.wav",
  },
  惊讶: {
    animation: "none",
    bubbleImage: "/pictures/animation/惊讶.webp",
    bubbleClass: "none",
    audio: "/audio_effects/察觉.wav",
  },
  平静: {
    animation: "none",
    bubbleImage: "none",
    bubbleClass: "none",
    audio: "none",
  },
  正常: {
    animation: "none",
    bubbleImage: "none",
    bubbleClass: "none",
    audio: "none",
  },
  AI思考: {
    animation: "none",
    bubbleImage: "/pictures/animation/AI思考.webp",
    bubbleClass: "none",
    audio: "/audio_effects/无语.wav",
  },
};
