/**
 * Live2D 配置的类型与判定（照搬 references/lingchat/src/types/live2d.ts）。
 * 本项目没有 WebGL 模型运行时，类型只在角色编辑草稿里落 localStorage，
 * 模型本体需要通过后端服务导入（见 Live2DSettings 的提示）。
 */

export interface Live2dMotionBinding {
  group: string;
  index: number;
  loop?: boolean;
  [key: string]: unknown;
}

export interface Live2dParameterBinding {
  parameter: string;
  gain?: number;
  [key: string]: unknown;
}

export interface Live2dEyeBlinkBinding {
  left: string;
  right: string;
  [key: string]: unknown;
}

export interface Live2dFocusAnchor {
  x: number;
  y: number;
  [key: string]: unknown;
}

/** 抚摸反应绑定：部位被抚摸时晃动并播这里绑定的表情 / 动作。两者都可留空。 */
export interface Live2dTouchBinding {
  group?: string;
  index?: number;
  loop?: boolean;
  expression?: string | null;
  [key: string]: unknown;
}

export interface Live2dVariant {
  model: string;
  default_expression?: string | null;
  expressions: Record<string, string>;
  motions: Record<string, Live2dMotionBinding>;
  focus_anchor?: Live2dFocusAnchor | null;
  touch_motions?: Record<string, Live2dTouchBinding>;
  [key: string]: unknown;
}

export interface Live2dSettings {
  version: 1;
  default_variant: string;
  variants: Record<string, Live2dVariant>;
  clothes_variants: Record<string, string>;
  [key: string]: unknown;
}

/** 一个变体里能从模型文件读到的表情 / 动作清单（导入模型后由后端返回）。 */
export interface Live2dVariantAssets {
  variant: string;
  model: string;
  expressions: string[];
  motions: Record<string, string[]>;
}

/** 角色形象：Live2D 模型，或静态立绘。主对话与桌宠各自一项。 */
export type AvatarDisplayMode = "live2d" | "image";

export function resolveLive2dVariant(
  settings: Live2dSettings,
  clothesName: string,
): Live2dVariant | undefined {
  const normalized = !clothesName || clothesName === "默认" ? "default" : clothesName;
  const mapped = settings.clothes_variants[normalized];
  const variantName = mapped || settings.default_variant;
  return settings.variants[variantName] ?? settings.variants[settings.default_variant];
}

/** 只有显式选了 image 才否定；缺省与未知值一律按 live2d（同原版语义）。 */
export function prefersLive2d(
  role: {
    live2d?: Live2dSettings | null;
    avatarMode?: AvatarDisplayMode | null;
    avatarModeP?: AvatarDisplayMode | null;
  },
  scene: "standard" | "pet" = "standard",
): boolean {
  const mode = scene === "pet" ? role.avatarModeP : role.avatarMode;
  return mode !== "image" && !!role.live2d;
}

/** 抚摸判定顺序 = 展示顺序，与 Live2DSettings / 运行时共用一份，免得漏配部位。 */
export const TOUCH_PART_ORDER = ["head", "body", "legs", "earLeft", "earRight"];
