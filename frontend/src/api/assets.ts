/**
 * 素材 API —— 对齐后端 cn.edu.zjut.interview.api.AssetsController（3 个端点）。
 *
 * 与原版（Tauri invoke 的 editor_list_asset_files / editor_upload_asset /
 * editor_delete_asset）的差异：
 * - Tauri 返回绝对路径，前端用 convertFileSrc 转 URL；这里后端直接给
 *   可访问的 HTTP url（frontend/public 即站点根，与 E6a 的 AssetFileWeb 同一套）。
 * - 上传走 dataURL（AppearanceTab 的裁剪上传同款通道），不走本地文件复制。
 */

/** 素材类别，键名与原版 AssetKind 一致。 */
export type AssetKind = "background" | "pic" | "music" | "sound" | "ambient";

/** 素材作用域：本剧本 / 全局。 */
export type AssetScope = "script" | "global";

/** 一个素材文件（后端 EditorAssetFileView）。 */
export interface AssetFile {
  name: string;
  /** 前端可直接 <img src> / <audio src> 的站点内路径 */
  url: string;
  size: number;
}

/** 五类素材的文件清单（后端 AssetsController.AssetFileIndex）。 */
export interface AssetFileIndex {
  background: AssetFile[];
  pic: AssetFile[];
  music: AssetFile[];
  sound: AssetFile[];
  ambient: AssetFile[];
}

/** 素材 API 失败：`status` 有值 = 后端明确拒绝，`message` 是后端中文文案。 */
export class AssetsApiError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = "AssetsApiError";
    this.status = status;
  }
}

/** 后端错误体是 `{error, message}`（见 AssetsController 的两个 ExceptionHandler）。 */
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

async function send(path: string, init?: RequestInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (e) {
    throw new AssetsApiError(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) throw new AssetsApiError(await readError(response), response.status);
  return response;
}

/** scope=script 时 key 必带（剧本级素材目录按 key 分）。 */
export function fetchAssetFiles(scope: AssetScope, key?: string): Promise<AssetFileIndex> {
  const q = new URLSearchParams({ scope });
  if (key) q.set("key", key);
  return send(`/api/editor/assets/files?${q.toString()}`).then((r) => r.json() as Promise<AssetFileIndex>);
}

/** File → dataURL（上传通道与外观页的裁剪上传一致）。 */
export function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new AssetsApiError("读取本地文件失败"));
    reader.readAsDataURL(file);
  });
}

/** 上传一个素材文件（后端按 kind / scope 决定落点目录）。 */
export async function uploadAsset(
  kind: AssetKind,
  scope: AssetScope,
  key: string,
  file: File,
): Promise<void> {
  const dataUrl = await fileToDataUrl(file);
  const response = await send("/api/editor/assets/upload", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ kind, scope, key, name: file.name, dataUrl }),
  });
  await response.json();
}

/** 删除一个素材文件（后端按 scope / kind 限定可删目录，越界返回 400）。 */
export async function deleteAsset(
  kind: AssetKind,
  scope: AssetScope,
  key: string,
  name: string,
): Promise<void> {
  const q = new URLSearchParams({ scope, kind, name });
  if (key) q.set("key", key);
  await send(`/api/editor/assets/file?${q.toString()}`, { method: "DELETE" });
}
