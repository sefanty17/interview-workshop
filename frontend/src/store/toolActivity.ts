/**
 * 工具调用活动状态（原 api/services/tool-settings 的前端快照）。
 * 需要后端服务上报；无后端时保持为空，顶部状态条不展示。
 */
import { ref } from "vue";

export interface ToolActivity {
  callId: string;
  tool: string;
  arguments: string;
  status: "running" | "success" | "failure";
}

export interface ToolCallPreparing {
  tool: string;
  chars: number;
}

export type ToolAccessMode = "manual" | "auto_approve" | "full_access";

export const currentToolActivity = ref<ToolActivity | null>(null);
export const toolCallPreparing = ref<ToolCallPreparing | null>(null);
export const currentToolAccessMode = ref<ToolAccessMode>("manual");
