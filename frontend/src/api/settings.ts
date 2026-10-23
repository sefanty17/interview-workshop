/**
 * 设置 API（规格 B4-BE §四）。
 *
 * 后端是权威：配置树与保存都走后端，前端 localStorage 只当离线缓存。
 * 后端由 vite 的 /api 代理转发到 8080，前端不接触任何凭据。
 */

/** 单个设置项，字段与后端 `ConfigSetting` 一一对应（value 一律是字符串）。 */
export interface ConfigSetting {
  key: string;
  group: string;
  label: string;
  /** 值类型，与原版 `setting_type` 一致：只有 text / number / bool 三个小写值 */
  type: "text" | "number" | "bool";
  value: string;
  defaultValue: string;
  min?: number;
  max?: number;
  options?: string[];
  description: string;
  /** 是否本项目扩展（原版没有的 key） */
  extension: boolean;
}

/** 一个子分类（`advanced` / `chat-enhance` / `interface` / `chat-rhythm`）。 */
export interface Subcategory {
  id: string;
  label: string;
  settings: ConfigSetting[];
}

/** 一个顶层分类（`llm` / `features`），对应原版 `Category`。 */
export interface Category {
  id: string;
  label: string;
  subcategories: Subcategory[];
}

/** `GET /api/settings/tree` 的返回体：两层结构，对应原版 `Category → Subcategory → settings`。 */
export interface ConfigTree {
  categories: Category[];
}

/**
 * 设置接口错误：`status` 有值 = 后端明确拒绝（body 里带 error 文案）；
 * `status` 为空 = 根本没连上后端（fetch 抛 TypeError），调用方据此切离线模式。
 */
export class ConfigApiError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = "ConfigApiError";
    this.status = status;
  }
}

/** 从失败响应里挖出后端的 error 文案（拿不到就退回状态码）。 */
async function readError(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { error?: string; message?: string };
        const message = body.error ?? body.message;
        if (message) return message;
      } catch {
        // 不是 JSON（比如 Spring 默认错误页），原样截一段
        return text.slice(0, 200);
      }
    }
  } catch {
    // 读 body 都失败，往后退到状态码
  }
  return `HTTP ${response.status}`;
}

/** 拉配置树（含每项的当前值）。 */
export async function fetchConfigTree(): Promise<ConfigTree> {
  let response: Response;
  try {
    response = await fetch("/api/settings/tree");
  } catch (e) {
    throw new ConfigApiError(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) {
    throw new ConfigApiError(await readError(response), response.status);
  }
  return (await response.json()) as ConfigTree;
}

/**
 * 保存一批设置。校验失败会抛 {@link ConfigApiError}，`message` 就是后端的中文文案
 * （「温度必须在 0.0–2.0 之间」这种），直接弹给用户。
 */
export async function saveConfig(values: Record<string, string>): Promise<void> {
  let response: Response;
  try {
    response = await fetch("/api/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(values),
    });
  } catch (e) {
    throw new ConfigApiError(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) {
    throw new ConfigApiError(await readError(response), response.status);
  }
}
