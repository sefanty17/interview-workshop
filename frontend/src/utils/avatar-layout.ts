/**
 * 立绘取景与布局规则（照搬 references/lingchat/src/utils/avatar-layout.ts）。
 * 舞台只有单角色，leftPercent 固定 50（居中）。
 */

/** 宽高比 <1 时按比例收缩取景高度（最低 80%），>=1 用 contain */
export function avatarObjectFit(aspectRatio: number): string {
  if (aspectRatio >= 1.0) return "contain";
  const percent = Math.max(80, 100 - (1.0 - aspectRatio) * 40);
  return `auto ${Math.round(percent)}%`;
}

/** 立绘层定位：水平居中 + 底部贴齐 + 缩放，竖屏/超宽屏做垂直补偿 */
export function standardAvatarStyle(
  role: { scale: number; offsetX: number; offsetY: number },
  viewport: { width: number; height: number },
  leftPercent = 50,
  castScale = 1,
  castOffsetY = 0,
): Record<string, string> {
  const ratio = viewport.width / viewport.height;
  const compensation =
    ratio < 1
      ? Math.round((viewport.height * Math.min(20, (1 - ratio) * 40)) / 100)
      : ratio >= 2
        ? Math.round((viewport.height * Math.min(10, (ratio - 2) * 20)) / 100)
        : 0;
  const scale = (role.scale ?? 1) * castScale;
  const top = role.offsetY - compensation;
  const downLimit = viewport.height * (1 - scale) - top;
  const castY = castOffsetY > 0 ? Math.min(castOffsetY, Math.max(0, downLimit)) : castOffsetY;
  return {
    left: `calc(${leftPercent}% + ${role.offsetX || 0}px)`,
    top: `${top + castY}px`,
    transform: `translateX(-50%) scale(${scale})`,
  };
}
