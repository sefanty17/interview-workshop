/**
 * 身份卡与角色关系 API —— 对齐原版 `api/services/player-identity.ts` 的类型与函数名，
 * 把 Tauri `invoke(...)` 换成后端 REST（端点见 `.workbuddy/plans/批次6-角色卡与身份关系对齐.md` §2.5）。
 *
 * 关系键：`ai:<角色 code>` = AI 角色，`me:<身份 id>` = 我的身份（见 `aiKey` / `meKey`）。
 */

// ============================================================
// 类型（逐字照抄原版 player-identity.ts）
// ============================================================

export interface PlayerIdentity {
  /** 不可变 id；新建时传空串，由后端生成 */
  id: string;
  name: string;
  subtitle: string;
  prompt: string;
  avatar?: string | null;
  /** 关系表：键为 `ai:<角色 code>` 或 `me:<身份 id>` */
  relations: Record<string, string>;
}

export interface PlayerIdentitySummary {
  id: string;
  name: string;
  subtitle: string;
  prompt: string;
  has_avatar: boolean;
  relation_count: number;
  is_current: boolean;
}

/** 后端 PlayerIdentityDto（camelCase，字段名与上面一一对应）。 */
interface PlayerIdentityDto {
  identityId: string;
  name: string;
  subtitle: string;
  prompt: string;
  avatar: string | null;
  relations: Record<string, string> | null;
}

interface PlayerIdentitySummaryDto {
  identityId: string;
  name: string;
  subtitle: string;
  prompt: string;
  hasAvatar: boolean;
  relationCount: number;
  isCurrent: boolean;
}

export const emptyPlayerIdentity = (): PlayerIdentity => ({
  id: "",
  name: "",
  subtitle: "",
  prompt: "",
  avatar: null,
  relations: {},
});

// ============================================================
// 错误与请求
// ============================================================

/** 身份接口失败：`code` 是后端 409 时的业务码（目前只有 `IDENTITY_IN_USE`：有面试记录引用）。 */
export class PlayerIdentityApiError extends Error {
  readonly status?: number;
  readonly code?: string;

  constructor(message: string, status?: number, code?: string) {
    super(message);
    this.name = "PlayerIdentityApiError";
    this.status = status;
    this.code = code;
  }
}

/** 后端错误体是 `{code, message}`；拿不到 message 就退回状态码。 */
async function readError(response: Response): Promise<{ message: string; code?: string }> {
  try {
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { code?: string; message?: string };
        return { message: body.message || `HTTP ${response.status}`, code: body.code };
      } catch {
        return { message: text.slice(0, 200) };
      }
    }
  } catch {
    // 读 body 失败就退回状态码
  }
  return { message: `HTTP ${response.status}` };
}

async function send(path: string, init?: RequestInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (e) {
    throw new PlayerIdentityApiError(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) {
    const { message, code } = await readError(response);
    throw new PlayerIdentityApiError(message, response.status, code);
  }
  return response;
}

async function callJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await send(path, init);
  return (await response.json()) as T;
}

async function callVoid(path: string, init?: RequestInit): Promise<void> {
  await send(path, init);
}

function jsonBody(value: unknown, method: "POST" | "PUT"): RequestInit {
  return {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(value),
  };
}

const q = (value: string): string => encodeURIComponent(value);

const base = "/api/player-identities";

// ============================================================
// DTO ⇄ 前端结构
// ============================================================

const toIdentity = (dto: PlayerIdentityDto): PlayerIdentity => ({
  id: dto.identityId,
  name: dto.name ?? "",
  subtitle: dto.subtitle ?? "",
  prompt: dto.prompt ?? "",
  avatar: dto.avatar ?? null,
  relations: dto.relations ?? {},
});

const toSummary = (dto: PlayerIdentitySummaryDto): PlayerIdentitySummary => ({
  id: dto.identityId,
  name: dto.name ?? "",
  subtitle: dto.subtitle ?? "",
  prompt: dto.prompt ?? "",
  has_avatar: Boolean(dto.hasAvatar),
  relation_count: dto.relationCount ?? 0,
  is_current: Boolean(dto.isCurrent),
});

// ============================================================
// 身份
// ============================================================

export async function listPlayerIdentities(): Promise<PlayerIdentitySummary[]> {
  const list = await callJson<PlayerIdentitySummaryDto[]>(base);
  return list.map(toSummary);
}

export async function getPlayerIdentity(id: string): Promise<PlayerIdentity | null> {
  const dto = await callJson<PlayerIdentityDto | null>(`${base}/${q(id)}`);
  return dto ? toIdentity(dto) : null;
}

/** `id` 为空串 = 新建（后端生成 id）；否则覆盖同 id 的记录。 */
export async function savePlayerIdentity(identity: PlayerIdentity): Promise<PlayerIdentity> {
  const body = {
    identityId: identity.id ?? "",
    name: identity.name,
    subtitle: identity.subtitle,
    prompt: identity.prompt,
    avatar: identity.avatar ?? null,
    relations: identity.relations ?? {},
  };
  const dto = identity.id
    ? await callJson<PlayerIdentityDto>(`${base}/${q(identity.id)}`, jsonBody(body, "PUT"))
    : await callJson<PlayerIdentityDto>(base, jsonBody(body, "POST"));
  return toIdentity(dto);
}

export async function deletePlayerIdentity(id: string): Promise<void> {
  await callVoid(`${base}/${q(id)}`, { method: "DELETE" });
}

/** 原地切换当前身份：历史保留。 */
export async function setCurrentPlayerIdentity(
  id: string,
): Promise<PlayerIdentitySummary | null> {
  const dto = await callJson<PlayerIdentitySummaryDto | null>(
    `${base}/${q(id)}/switch`,
    { method: "POST" },
  );
  return dto ? toSummary(dto) : null;
}

/**
 * 「用它开新对话」：本项目没有多存档，映射为「切到该身份」；
 * 清当前会话由调用方复用现有 clearSession()。
 */
export const startNewGameWithIdentity = setCurrentPlayerIdentity;

// ============================================================
// 角色关系
// ============================================================

/** `interviewerCode` 是角色 code（如 `strict`），不是目录名。 */
const relationsBase = (interviewerCode: string): string =>
  `/api/interviewers/${q(interviewerCode)}/relations`;

export async function getRoleRelations(interviewerCode: string): Promise<Record<string, string>> {
  return await callJson<Record<string, string>>(relationsBase(interviewerCode));
}

/** 整份覆盖：body 就是完整的 `{关系键: 文本}` 映射。 */
export async function saveRoleRelations(
  interviewerCode: string,
  relations: Record<string, string>,
): Promise<void> {
  await callVoid(relationsBase(interviewerCode), jsonBody(relations, "PUT"));
}

export const aiKey = (characterCode: string) => `ai:${characterCode}`;
export const meKey = (identityId: string) => `me:${identityId}`;
