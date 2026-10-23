/**
 * 剧本导入 API（规格 B5-BE §2.6 / §三）。
 *
 * 后端在做完解析 + 落库后同步返回覆盖度报告：绿项照跑，黄/红项原样留在库里、
 * 只在报告里点名。所以这里不做任何"失败重试"或"修补"，把报告原样交给弹窗。
 */

/** 一条事件级结论；绿项只有 note，黄/红项只有 issue + detail。 */
export interface ImportReportItem {
  /** 章节 code，保留相对路径（如 `Intro/intro`）；剧本级问题时是空串 */
  chapter: string;
  /** 事件在章节 events 里的下标；剧本级问题是 -1 */
  index: number;
  type: string;
  note?: string;
  issue?: string;
  detail?: string;
}

export interface ImportReportSummary {
  green: number;
  yellow: number;
  red: number;
}

/** `report` 字段，与后端 `ImportReport` 一一对应。 */
export interface ImportReport {
  scriptCode: string;
  scriptName: string;
  chapters: number;
  events: number;
  green: ImportReportItem[];
  yellow: ImportReportItem[];
  red: ImportReportItem[];
  summary: ImportReportSummary;
}

/** `POST /api/scripts/import` 的返回体，与后端 `ImportResult` 一一对应。 */
export interface ImportResult {
  scriptCode: string;
  scriptName: string;
  /** false = 这个剧本之前导过，本次是重建章节 */
  created: boolean;
  scriptId: number;
  report: ImportReport;
}

/** 导入失败：`status` 有值 = 后端明确拒绝，`message` 是后端中文文案。 */
export class ScriptImportError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = "ScriptImportError";
    this.status = status;
  }
}

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

/** 上传 zip 导入剧本；`scriptCode` 留空则由后端分配 `imported-N`。 */
export async function importScriptZip(file: File, scriptCode?: string): Promise<ImportResult> {
  const form = new FormData();
  form.append("file", file);
  if (scriptCode) form.append("scriptCode", scriptCode);

  let response: Response;
  try {
    response = await fetch("/api/scripts/import", { method: "POST", body: form });
  } catch (e) {
    throw new ScriptImportError(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) {
    throw new ScriptImportError(await readError(response), response.status);
  }
  return (await response.json()) as ImportResult;
}
