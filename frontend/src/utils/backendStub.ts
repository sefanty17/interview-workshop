/** 需要真后端（模型文件读写、TTS 合成、资源上传）的功能统一在这里弹提示，不静默失败。 */
import { alertDialog } from "../store/dialogStore";

export function requireBackend(detail: string): Promise<void> {
  return alertDialog("需要后端服务，当前未接入", detail);
}
