/**
 * 剧本音频驱动（B7 任务二）：`script:music` / `script:sound` / `script:ambient`
 * 的 Web 等价物。
 *
 * 原版是 Tauri 桌面端：事件把文件路径写进 uiStore，由 GameBackground.vue 统一
 * convertFileSrc 后挂 <audio>。Web 上换成 HTMLAudioElement，路径直接当成 URL
 * （前端 public / 后端静态资源都能吃）。
 *
 * 资源不存在（404）时静默忽略：只 `console.debug`，不弹错、不打断剧本推进。
 */
import { watch } from "vue";
import { roomStore } from "./roomStore";

/** 剧本里的资源路径 → 浏览器可请求的 URL（相对路径补前导斜杠）。 */
export function resolveAssetUrl(path: string): string {
  const p = (path ?? "").trim();
  if (!p) return "";
  if (/^(https?:|data:|blob:)/i.test(p) || p.startsWith("/")) return p;
  return `/${p}`;
}

function clampVolume(volume: number): number {
  if (!Number.isFinite(volume)) return 1;
  return Math.max(0, Math.min(1, volume / 100));
}

/** 播放被浏览器策略拦下（未交互 / 无音源）时静默失败。 */
function playSilently(el: HTMLAudioElement): void {
  const result = el.play();
  if (result && typeof result.catch === "function") result.catch(() => undefined);
}

/** 淡出后停掉并释放（原版 ambient 的 fade=true 语义）。 */
function fadeOut(el: HTMLAudioElement, fade: boolean): void {
  if (!fade) {
    el.pause();
    el.removeAttribute("src");
    return;
  }
  const step = el.volume / 10;
  const timer = window.setInterval(() => {
    if (el.volume > step) {
      el.volume = Math.max(0, el.volume - step);
      return;
    }
    window.clearInterval(timer);
    el.pause();
    el.removeAttribute("src");
  }, 50);
}

/** 静默标记：404 / 解码失败都只记 debug，不影响剧本继续。 */
function muteErrors(el: HTMLAudioElement, kind: string, src: string): void {
  el.addEventListener("error", () => console.debug(`[room-audio] ${kind} 资源不可用`, src));
}

/**
 * 挂上音频驱动，返回注销函数（组件卸载时调用，停掉所有音轨）。
 *
 * 状态全部从 roomStore 读：bgm / sound / ambient 三个字段由事件分发写入，
 * 这里只负责把它们翻译成 HTMLAudioElement 的播放操作。
 */
export function attachRoomAudio(): () => void {
  let bgm: HTMLAudioElement | null = null;
  let bgmSrc = "";
  const ambient = new Map<string, HTMLAudioElement>();
  const oneShots = new Set<HTMLAudioElement>();

  function stopBgm(): void {
    if (!bgm) return;
    bgm.pause();
    bgm.removeAttribute("src");
    bgm = null;
    bgmSrc = "";
  }

  const stopBgmWatch = watch(
    () => roomStore.bgm.src,
    (src) => {
      const url = resolveAssetUrl(src);
      if (!url) {
        stopBgm();
        return;
      }
      if (url === bgmSrc) return;
      stopBgm();
      bgmSrc = url;
      bgm = new Audio(url);
      bgm.loop = true;
      bgm.playbackRate = roomStore.bgm.rate || 1;
      muteErrors(bgm, "music", url);
      playSilently(bgm);
    },
    { immediate: true },
  );

  const rateWatch = watch(
    () => roomStore.bgm.rate,
    (rate) => {
      if (bgm) bgm.playbackRate = rate || 1;
    },
  );

  const playingWatch = watch(
    () => roomStore.bgm.playing,
    (playing) => {
      if (!bgm) return;
      if (playing) playSilently(bgm);
      else bgm.pause();
    },
  );

  // 一次性音效：token 自增触发（同一段音效连发两次也能各播一次）
  const soundWatch = watch(
    () => roomStore.sound.token,
    () => {
      const url = resolveAssetUrl(roomStore.sound.src);
      if (!url) return;
      const el = new Audio(url);
      oneShots.add(el);
      muteErrors(el, "sound", url);
      el.addEventListener("ended", () => {
        oneShots.delete(el);
        el.removeAttribute("src");
      });
      el.addEventListener("error", () => oneShots.delete(el));
      playSilently(el);
    },
  );

  // 环境音多轨：按 src 求差集，新增/更新各轨音量与循环，消失的轨淡出
  const ambientWatch = watch(
    () => roomStore.ambient,
    (tracks) => {
      const wanted = new Map(tracks.map((track) => [resolveAssetUrl(track.src), track]));
      for (const [url, el] of [...ambient]) {
        if (wanted.has(url)) continue;
        fadeOut(el, true);
        ambient.delete(url);
      }
      for (const [url, track] of wanted) {
        if (!url) continue;
        let el = ambient.get(url);
        if (!el) {
          el = new Audio(url);
          muteErrors(el, "ambient", url);
          ambient.set(url, el);
          playSilently(el);
        }
        el.loop = track.loop;
        el.volume = clampVolume(track.volume);
      }
    },
    { deep: true, immediate: true },
  );

  return () => {
    stopBgmWatch();
    rateWatch();
    playingWatch();
    soundWatch();
    ambientWatch();
    stopBgm();
    for (const [url, el] of ambient) {
      fadeOut(el, false);
      ambient.delete(url);
    }
    for (const el of oneShots) {
      el.pause();
      el.removeAttribute("src");
    }
    oneShots.clear();
  };
}
