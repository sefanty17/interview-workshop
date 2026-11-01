/**
 * 舞台媒体快照恢复（issue #13）。
 *
 * 剧本的 6 类媒体事件（background / background-effect / music / sound / ambient / present-pic）
 * 后端都写进了 `interview_session.media_state`；进入场次（含刷新续场）时读一次，
 * 把 roomStore 拨回事件切换时的样子，再继续消费后续 SSE。
 *
 * `sound` 是一次性播放，刷新后重放会凭空多一声，故只记录不恢复（与后端同一口径）。
 */
import type { SessionMediaState } from "../api/chat";
import { roomStore } from "./roomStore";

/** 当前进行中的后端场次 —— 刷新后靠它找回同一个 sessionId，而不是新建一场。 */
const ACTIVE_SESSION_KEY = "interview-active-session-v1";

export interface ActiveSessionRef {
  sessionId: number;
  scriptCode: string | null;
}

/** 读当前进行中的场次；没有 / 数据坏了都返回 null。 */
export function readActiveSession(): ActiveSessionRef | null {
  try {
    const raw = localStorage.getItem(ACTIVE_SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<ActiveSessionRef> | null;
    if (!parsed || typeof parsed.sessionId !== "number") return null;
    return {
      sessionId: parsed.sessionId,
      scriptCode: typeof parsed.scriptCode === "string" ? parsed.scriptCode : null,
    };
  } catch {
    return null;
  }
}

/** 记录当前进行中的场次（建场次后、恢复成功后都写一次）。 */
export function rememberActiveSession(sessionId: number, scriptCode: string | null): void {
  try {
    localStorage.setItem(ACTIVE_SESSION_KEY, JSON.stringify({ sessionId, scriptCode }));
  } catch {
    /* 隐私模式下 localStorage 可能不可写，恢复不了就退回新建场次 */
  }
}

/** 场次收尾（正常结束 / 后端已判定不存在）时清掉，避免下次进来续一场已结束的面试。 */
export function forgetActiveSession(): void {
  try {
    localStorage.removeItem(ACTIVE_SESSION_KEY);
  } catch {
    /* 同上 */
  }
}

/**
 * 把后端快照写回 roomStore。返回是否真的改过舞台（false = 这场没有任何媒体事件，
 * 调用方不必额外处理）。
 */
export function applyMediaState(state: SessionMediaState | null | undefined): boolean {
  if (!state) return false;

  if (state.background) {
    roomStore.backgroundUrl = state.background.imagePath ?? "";
    roomStore.backgroundTransition = Number.isFinite(state.background.transition)
      ? state.background.transition
      : 0;
  }

  if (state.backgroundEffect) {
    roomStore.backgroundEffect = state.backgroundEffect.effect ?? "";
  }

  if (state.music) {
    const src = state.music.musicPath ?? "";
    roomStore.bgm = {
      src,
      playing: !!src && state.music.playing !== false,
      rate: state.music.playbackSpeed ?? 1,
    };
  }

  if (Array.isArray(state.ambient)) {
    roomStore.ambient = state.ambient
      .filter((track) => !!track?.ambientPath)
      .map((track) => ({
        src: track.ambientPath,
        volume: track.volume ?? 100,
        loop: track.loop ?? true,
        fade: track.fade ?? true,
      }));
  }

  if (state.presentPic) {
    roomStore.presentPic = state.presentPic.imagePath ?? "";
    roomStore.presentPicScale = state.presentPic.scale ?? 1;
  }

  return true;
}
