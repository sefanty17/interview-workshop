/**
 * 媒体资源可用性（issue #13）：剧本里的音频 / 图片路径其实可能不存在，
 * 播放器加载失败时在这里记一笔，声效面板据此渲染「占位灰条」而不是假装播起来了。
 *
 * 只记内存，不落库：每次进房间重新探测一次，避免把一次网络抖动永久标成缺失。
 */
import { reactive } from "vue";

export const mediaAvailability = reactive({
  /** 已确认不可用的资源 URL（键为 resolveAssetUrl 后的地址） */
  missing: {} as Record<string, true>,
});

/** 标记某个资源不可用（404 / 解码失败）。 */
export function markMediaMissing(url: string): void {
  const key = url.trim();
  if (key) mediaAvailability.missing[key] = true;
}

/** 该资源是否已被确认不可用。 */
export function isMediaMissing(url: string): boolean {
  const key = url.trim();
  return !!key && !!mediaAvailability.missing[key];
}
