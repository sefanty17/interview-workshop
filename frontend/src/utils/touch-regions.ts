/**
 * 静态立绘触摸区域：形状归一化、旧坐标换算与命中判定，全部是纯函数。
 * 照搬 references/lingchat/src/components/game/standard/touch-regions.ts。
 */

export type Vec2 = [number, number];
export type TouchPolygon = Vec2[];

/** 一个部位的触摸区域。坐标相对立绘图片，0..1，可有多块互不相连的多边形，共用同一句提示词。 */
export interface TouchRegion {
  message: string;
  polygons: TouchPolygon[];
}

/** 部位名到区域 */
export type CostumeRegions = Record<string, TouchRegion>;
/** 服装名到部位表 */
export type TouchCostumes = Record<string, CostumeRegions>;

/** 根目录服装的显示名，与后端 canonical_clothes 的取值一致。 */
export const DEFAULT_COSTUME = "默认";

/** 立绘的取景方式：contain 是宽屏那一支，height 对应窄屏的 auto NN%。 */
export type Fit = { kind: "contain" } | { kind: "height"; percent: number };

/** 立绘在盒子里的实际落位，以盒子的 0..1 表示。 */
export interface ImageRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 换算旧坐标所需的几何。 */
export interface LegacyFrame {
  boxAspect: number;
  imageAspect: number;
  fit: Fit;
}

/** 空串、「默认」与大小写不限的 default 都指向根目录那套立绘。 */
export function resolveCostumeKey(raw?: string | null): string {
  const trimmed = (raw ?? "").trim();
  if (!trimmed || trimmed === DEFAULT_COSTUME || trimmed.toLowerCase() === "default") {
    return DEFAULT_COSTUME;
  }
  return trimmed;
}

/** 从 ImageAcrossFade 的 object-fit 值反推取景方式，认不出来的一律按 contain。 */
export function fitFromObjectFit(objectFit: string): Fit {
  const percent = /auto\s+(\d+(?:\.\d+)?)%/.exec(objectFit);
  return percent ? { kind: "height", percent: Number(percent[1]) } : { kind: "contain" };
}

/** 立绘在盒子内的落位。等比缩放、水平居中、贴底，与 background-position: center bottom 一致。 */
export function imageRectInBox(boxAspect: number, imageAspect: number, fit: Fit): ImageRect {
  const relative = imageAspect / boxAspect;
  const height =
    fit.kind === "contain" ? Math.min(1, 1 / relative) : Math.min(1, fit.percent / 100);
  const width = height * relative;
  return { x: (1 - width) / 2, y: 1 - height, width, height };
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function parsePolygon(raw: unknown): TouchPolygon | null {
  if (!Array.isArray(raw)) return null;
  const polygon: TouchPolygon = [];
  for (const point of raw) {
    if (!Array.isArray(point)) continue;
    const [x, y] = point;
    if (!isFiniteNumber(x) || !isFiniteNumber(y)) continue;
    polygon.push([x, y]);
  }
  return polygon.length >= 3 ? polygon : null;
}

function parseRegion(raw: unknown): TouchRegion | null {
  if (!raw || typeof raw !== "object") return null;
  const { message, polygons } = raw as { message?: unknown; polygons?: unknown };
  if (!Array.isArray(polygons)) return null;
  const parsed: TouchPolygon[] = [];
  for (const candidate of polygons) {
    const polygon = parsePolygon(candidate);
    if (polygon) parsed.push(polygon);
  }
  if (!parsed.length) return null;
  return { message: typeof message === "string" ? message : "", polygons: parsed };
}

/** 旧形状：部位名下直接挂 X/Y 数组，外加 clothesName、message 与截图分辨率。 */
interface LegacyPart {
  X: number[];
  Y: number[];
  clothesName?: string;
  message?: string;
  windowWidth?: number;
  windowHeight?: number;
}

function isLegacyPart(value: unknown): value is LegacyPart {
  return !!value && typeof value === "object" && Array.isArray((value as LegacyPart).X);
}

function legacyPolygonToImage(part: LegacyPart, frame: LegacyFrame): TouchPolygon | null {
  const refWidth = part.windowWidth ?? 0;
  const refHeight = part.windowHeight ?? 0;
  if (!(refWidth > 0) || !(refHeight > 0)) return null;
  const reference = refWidth / refHeight;
  const rect = imageRectInBox(frame.boxAspect, frame.imageAspect, frame.fit);
  const points: TouchPolygon = [];
  const count = Math.min(part.X.length, part.Y.length);
  for (let index = 0; index < count; index += 1) {
    const boxX = 0.5 + (part.X[index]! - 0.5) * (reference / frame.boxAspect);
    const boxY = part.Y[index]!;
    points.push([(boxX - rect.x) / rect.width, (boxY - rect.y) / rect.height]);
  }
  return points.length >= 3 ? points : null;
}

export function parseBodyPart(
  raw: unknown,
  frame?: LegacyFrame | null,
): { costumes: TouchCostumes; hadLegacy: boolean } {
  const costumes: TouchCostumes = {};
  let hadLegacy = false;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return { costumes, hadLegacy };

  const merge = (costume: string, parts: CostumeRegions) => {
    if (!Object.keys(parts).length) return;
    costumes[costume] = { ...costumes[costume], ...parts };
  };

  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (isLegacyPart(value)) {
      hadLegacy = true;
      if (!frame) continue;
      const polygon = legacyPolygonToImage(value, frame);
      if (!polygon) continue;
      merge(resolveCostumeKey(value.clothesName), {
        [key]: {
          message: typeof value.message === "string" ? value.message : "",
          polygons: [polygon],
        },
      });
      continue;
    }
    if (!value || typeof value !== "object" || Array.isArray(value)) continue;
    const parts: CostumeRegions = {};
    for (const [part, region] of Object.entries(value as Record<string, unknown>)) {
      const parsed = parseRegion(region);
      if (parsed) parts[part] = parsed;
    }
    merge(resolveCostumeKey(key), parts);
  }
  return { costumes, hadLegacy };
}

const PRECISION = 1e5;

function round(value: number): number {
  const clamped = Math.max(0, Math.min(1, value));
  return Math.round(clamped * PRECISION) / PRECISION;
}

/** 落盘用的形状。夹到 0..1、丢掉顶点不足三个的多边形，空区域与空服装一并丢掉。 */
export function serializeBodyPart(costumes: TouchCostumes): Record<string, unknown> {
  const serialized: Record<string, unknown> = {};
  for (const [costume, parts] of Object.entries(costumes)) {
    const serializedParts: Record<string, unknown> = {};
    for (const [part, region] of Object.entries(parts)) {
      const polygons = region.polygons
        .map((polygon) => polygon.map(([x, y]) => [round(x), round(y)] as Vec2))
        .filter((polygon) => polygon.length >= 3);
      if (!polygons.length) continue;
      serializedParts[part] = { message: region.message, polygons };
    }
    if (Object.keys(serializedParts).length) serialized[costume] = serializedParts;
  }
  return serialized;
}

/** 射线法。坐标同为图片归一化，多边形按顶点顺序闭合。 */
function pointInPolygon(x: number, y: number, polygon: TouchPolygon): boolean {
  let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i, i += 1) {
    const [sx, sy] = polygon[i]!;
    const [tx, ty] = polygon[j]!;
    if ((sy < y && ty >= y) || (sy >= y && ty < y)) {
      const cross = sx + ((y - sy) * (tx - sx)) / (ty - sy);
      if (cross > x) inside = !inside;
    }
  }
  return inside;
}

/** 命中的部位名，按插入序先到先得；都没中返回 null。 */
export function hitRegion(regions: CostumeRegions, x: number, y: number): string | null {
  for (const [part, region] of Object.entries(regions)) {
    for (const polygon of region.polygons) {
      if (pointInPolygon(x, y, polygon)) return part;
    }
  }
  return null;
}

/** 编辑器显示全部重叠命中，顺序与运行时 hitRegion 的先到先得一致。 */
export function hitRegions(regions: CostumeRegions, x: number, y: number): string[] {
  return Object.entries(regions)
    .filter(([, region]) => region.polygons.some((polygon) => pointInPolygon(x, y, polygon)))
    .map(([part]) => part);
}

/** 编辑器与运行时共用触摸消息的空值回退。 */
export function touchRegionMessage(message: string | undefined, userName: string): string {
  return message || `${userName}戳了一下你`;
}
