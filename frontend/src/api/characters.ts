/**
 * 角色卡 API —— 对齐后端 cn.edu.zjut.interview.api.CharacterController（5 个端点）。
 *
 * 与原版（Tauri 直接读写 `characters/<folder>/settings.yml`）的差异：
 * - 这里的 `folder` 是**目录名**（中文，如 `风雪`），与前端 `interviewer.code`（如 `strict`）
 *   的映射写死在 store/characters.ts 的常量表里（后端 CharacterCardService.FOLDER_TO_CODE）。
 * - 列表/详情只读；新建/保存/删除为写操作，本项目当前只有房间消耗只读部分。
 */

/** `GET /api/editor/characters` 的一项（后端 dto/character/CharacterCardSummary）。 */
export interface CharacterCardSummary {
  folder: string;
  aiName: string;
  aiSubtitle?: string;
  userName?: string;
  userSubtitle?: string;
  title?: string;
  avatarMode?: string;
}

/** `GET /api/editor/characters/{folder}` 的一张全卡（后端 dto/character/CharacterCard）。 */
export interface CharacterCard extends CharacterCardSummary {
  info?: string;
  scale?: number;
  offsetX?: number;
  offsetY?: number;
  bubbleTop?: number;
  bubbleLeft?: number;
  /** TTS 声线表；不同角色的结构不一致，保留原样由调用方解释 */
  voiceModels?: Record<string, unknown>;
  bodyPart?: unknown;
  clothes?: unknown;
  /** settings.yml 全文（原版「直接编辑文本框」那一档） */
  rawYaml?: string;
}

/** 按字段覆盖时的 body（键名沿用原版 settings.yml 的 snake_case） */
export type CharacterFields = Record<string, unknown>;

/** 角色卡 API 失败：`status` 有值 = 后端明确拒绝，`message` 是后端中文文案。 */
export class CharactersApiError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = "CharactersApiError";
    this.status = status;
  }
}

/** 后端错误体是 `{error, message}`（见 CharacterController 的两个 ExceptionHandler）。 */
async function readError(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { error?: string; message?: string };
        const message = body.message ?? body.error;
        if (message) return message;
      } catch {
        return text.slice(0, 200);
      }
    }
  } catch {
    // 读 body 失败就退回状态码
  }
  return `HTTP ${response.status}`;
}

/** 发请求并做统一的失败包装；返回原始 response 供调用方决定怎么读 body。 */
async function send(path: string, init?: RequestInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (e) {
    throw new CharactersApiError(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) throw new CharactersApiError(await readError(response), response.status);
  return response;
}

async function callJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await send(path, init);
  return (await response.json()) as T;
}

async function callVoid(path: string, init?: RequestInit): Promise<void> {
  await send(path, init);
}

/** 写操作的公共请求参数（只有写操作带 body）。 */
function jsonBody(value: unknown, method: "POST" | "PUT"): RequestInit {
  return {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(value),
  };
}

/** 目录名是中文，必须转义后再放进路径段。 */
const q = (value: string): string => encodeURIComponent(value);

const base = (folder: string): string => `/api/editor/characters/${q(folder)}`;

// ============================================================
// 5 个端点
// ============================================================

export function fetchCharacters(): Promise<CharacterCardSummary[]> {
  return callJson<CharacterCardSummary[]>("/api/editor/characters");
}

export function fetchCharacter(folder: string): Promise<CharacterCard> {
  return callJson<CharacterCard>(base(folder));
}

/**
 * 新建一张卡（后端返回 201 + 新建后的卡，这里不需要回读结果）。
 * `fields` 为空即建一个空壳目录（原版「新建角色」同样是先给空壳再编辑）。
 */
export async function createCharacter(folder: string, fields?: CharacterFields): Promise<void> {
  await callVoid(base(folder), jsonBody(fields ? { fields } : {}, "POST"));
}

/** 保存一张卡：rawYaml 与 fields 二选一，两个都给后端以 rawYaml 为准。 */
export async function saveCharacter(
  folder: string,
  body: { fields?: CharacterFields; rawYaml?: string },
): Promise<void> {
  await callVoid(base(folder), jsonBody(body, "PUT"));
}

export async function deleteCharacter(folder: string): Promise<void> {
  await callVoid(base(folder), { method: "DELETE" });
}
