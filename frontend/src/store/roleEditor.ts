/**
 * 角色编辑草稿的本地存储。
 *
 * 原版走后端 beginCharacterEdit / commitCharacterEdit 的草稿区；本项目没有后端，
 * 草稿 = localStorage，键 interview-lab-role-overrides，结构 { [角色 code]: RoleSettings }。
 * 基线值（人设、布局数值）来自 data/interviewers.ts，改动只写覆盖层，底板数据不动。
 */
import { findInterviewer } from "../data/interviewers";
import type { AvatarDisplayMode, Live2dSettings } from "../types/live2d";

const STORAGE_KEY = "interview-lab-role-overrides";

/** 服装条目：名字 + 服装提示词（原版 settings.yml 的 clothes 结构） */
export interface CostumeEntry {
  name: string;
  prompt: string;
}

/** 与原版 settings.yml 的角色配置同源，键名沿用原版，方便日后对接后端 */
export interface RoleSettings {
  character_folder: string;
  ai_name: string;
  ai_subtitle: string;
  title: string;
  info: string;
  /** 我们特有的：卡片标签（角色列表页与详情头部的展示用） */
  tags: string[];
  system_prompt: string;
  system_prompt_example: string;
  system_prompt_example_old: string;
  avatar_mode: AvatarDisplayMode;
  scale: number;
  offset_x: number;
  offset_y: number;
  bubble_top: number;
  bubble_left: number;
  thinking_message: string;
  avatar_mode_p: AvatarDisplayMode;
  pet_frameless: boolean;
  scale_p: number;
  offset_x_p: number;
  offset_y_p: number;
  tts_type: string;
  voice_lang: string;
  voice_dialect: string;
  voice_models: Record<string, unknown>;
  body_part: Record<string, unknown>;
  clothes: CostumeEntry[];
  live2d: Live2dSettings | null;
  [key: string]: unknown;
}

/** 一份可编辑的角色配置：字段全部来自角色卡原文（seed → data/interviewers.ts） */
export function defaultRoleSettings(code: string): RoleSettings {
  const persona = findInterviewer(code);
  return {
    character_folder: code,
    ai_name: persona.name,
    ai_subtitle: persona.subtitle,
    title: persona.cardTitle,
    // dev 的 info 是 settings.yml 的多行块（`|‑`），原样保留，不压成一行
    info: persona.cardInfoText,
    tags: [persona.displayRole, persona.subtitle].filter(Boolean),
    // 人设全文＝角色卡设定原文，逐字；用户改过之后交给覆盖层
    system_prompt: persona.personaPrompt,
    system_prompt_example: persona.exampleDialogue,
    system_prompt_example_old: persona.systemPromptExampleOld ?? "",
    avatar_mode: "image",
    scale: persona.scale,
    offset_x: persona.offsetX,
    offset_y: persona.offsetY,
    bubble_top: persona.bubbleTop,
    bubble_left: persona.bubbleLeft,
    thinking_message: persona.thinkingMessage ?? "",
    avatar_mode_p: "image",
    pet_frameless: false,
    scale_p: persona.scale,
    offset_x_p: 0,
    offset_y_p: 0,
    tts_type: "gsv",
    voice_lang: "zh",
    voice_dialect: "",
    voice_models: {},
    body_part: {},
    clothes: [],
    live2d: null,
  };
}

type OverrideTable = Record<string, Partial<RoleSettings>>;

function readTable(): OverrideTable {
  try {
    const raw = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "{}");
    return raw && typeof raw === "object" && !Array.isArray(raw) ? (raw as OverrideTable) : {};
  } catch {
    return {};
  }
}

function writeTable(table: OverrideTable): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(table));
}

/** 读一份可编辑的角色配置（默认值 + 已保存的覆盖层）。
 *  user_name / user_subtitle 已从字段表移除（dev 5e66d3c2），老覆盖层里的残留键不取。 */
export function loadRoleSettings(code: string): RoleSettings {
  const saved = readTable()[code] ?? {};
  const override = { ...saved };
  delete override.user_name;
  delete override.user_subtitle;
  return { ...defaultRoleSettings(code), ...override };
}

/** 落盘一份角色配置。null / undefined 之外的字段原样保存。 */
export function saveRoleSettings(code: string, settings: RoleSettings): void {
  const table = readTable();
  table[code] = settings;
  writeTable(table);
}

/** 清掉覆盖层，回到静态角色数据的默认值 */
export function resetRoleSettings(code: string): void {
  const table = readTable();
  delete table[code];
  writeTable(table);
}

/** 该角色是否有过本地改动 */
export function hasRoleOverride(code: string): boolean {
  return Boolean(readTable()[code]);
}

