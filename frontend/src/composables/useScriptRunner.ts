/**
 * 剧本事件流消费器（原 `views/InterviewRoomView.vue` 的 `runScript()`，E5b 抽出）。
 *
 * 「SSE 事件 → roomStore」全项目只有这一份实现：正式房间（`InterviewRoomView`）与
 * 剧本编辑器试玩（`PreviewStage`）共用，保证试玩画面与正式游玩逐帧一致。
 * 房间特有的落点（章节名 / 进度条 / 自动收尾 / 历史消息）通过 hooks 交回调用方，
 * hook 没传就跳过。
 */

import type {
  ReplyEvent,
  ScriptAmbientPayload,
  ScriptBackgroundEffectPayload,
  ScriptBackgroundPayload,
  ScriptChapterChangeEvent,
  ScriptChoicePayload,
  ScriptInputEvent,
  ScriptModifyCharacterEvent,
  ScriptMusicPayload,
  ScriptNarrationEvent,
  ScriptPlayerPayload,
  ScriptPresentPicPayload,
  ScriptProgressEvent,
  ScriptSoundPayload,
  StreamEvent,
} from "../api/chat";
import { pushLine, roomStore, setPresentRoles, speakAs, type RoomLine } from "../store/roomStore";

export interface ScriptRunHooks {
  /** 角色标识 → 舞台 code（房间按本场人设解析；试玩按剧本绑定人设解析） */
  resolveRole(raw: unknown): string | null;
  /** 每句落历史（房间写 store.session.messages；试玩不传） */
  recordLine?(line: RoomLine): void;
  /** script:chapter-change */
  onChapter?(chapter: { code: string; name: string } | null): void;
  /** script:progress 的百分比 */
  onProgress?(pct: number): void;
  /** 引擎停在 input / choice 阻塞点 */
  onBlocked?(blocked: boolean): void;
  /** script:end */
  onEnd?(): void;
  /** ai:error */
  onError?(payload: unknown): void;
}

/**
 * 已收到但还没放出的 script:choice（同一批台词读完再出选项，照原版事件队列顺序）。
 *
 * 这份状态跟着「流消费器」走而不是跟着某个视图走：选项的挂起与放出是流语义的一部分，
 * 房间与试玩必须共用同一份，否则两处各存一份就会各自漂移。
 */
let pendingChoice: ScriptChoicePayload | null = null;

/** 引擎停在 input 阻塞点（收到过 script:input / script:choice） */
let scriptBlocked = false;

/** 把挂起的选项放进房间（GameChoices 读 roomStore.choices 渲染） */
export function applyPendingChoice(): void {
  if (!pendingChoice) return;
  const data = pendingChoice;
  pendingChoice = null;
  roomStore.choices = (data.choices ?? []).filter((item) => !!item?.text);
  roomStore.allowFree = data.allowFree === true;
  roomStore.choiceDuration = typeof data.duration === "number" ? data.duration : null;
}

/**
 * 消费一段剧本事件流（开演 / 输入 / 选项三条入口共用）。返回 true 表示后端已经接管
 * 这次提交（产出了台词，或已停在某处等输入），不要再走本地假回复；false 交回调用方降级。
 *
 * 与 sendToBackend 同一套节奏：先收在 buffer 里，流结束后从第一句起逐句播放。
 */
export async function runScriptStream(
  stream: AsyncGenerator<StreamEvent>,
  hooks: ScriptRunHooks,
): Promise<boolean> {
  const buffered: RoomLine[] = [];
  let ended = false;

  // 每次提交都从「没有阻塞」重新判定（改造前是 handleSend 在调 runScript 前置 false，
  // 位置等价：runScript 只有 handleSend 与 enterScriptScene 两个调用点，后者的初始值本就是 false）
  scriptBlocked = false;

  try {
    for await (const ev of stream) {
      if (ev.event === "script:narration") {
        const data = ev.data as ScriptNarrationEvent;
        if (!data?.text) continue;
        buffered.push({ role: "ai", text: data.text, narration: true, final: true });
      } else if (ev.event === "ai:reply") {
        const data = ev.data as ReplyEvent;
        if (!data?.message) continue;
        buffered.push({
          role: "ai",
          text: data.message,
          motion: data.motionText || undefined,
          emotion: data.emotion,
          final: data.isFinal,
          speaker: hooks.resolveRole(data.senderRoleId) ?? undefined,
        });
      } else if (ev.event === "script:modify-character") {
        const data = ev.data as ScriptModifyCharacterEvent;
        // 在场角色是舞台的唯一真相源（show/hide 已由后端算好）；MAIN 再兜底解析一次
        if (Array.isArray(data.presentRoles)) {
          setPresentRoles(
            data.presentRoles.map((code) => hooks.resolveRole(code)).filter((c): c is string => !!c),
          );
        }
        // duration 单位是秒（原版口径），后端没下发就用 0.5s 缺省
        roomStore.characterTransition =
          typeof data.duration === "number" && data.duration > 0 ? data.duration : 0.5;
        if (data.emotion) {
          const speaker = hooks.resolveRole(data.characterId) ?? roomStore.activeSpeaker;
          if (speaker) speakAs(speaker, data.emotion);
          else roomStore.showCharacterEmotion = data.emotion;
        }
      } else if (ev.event === "script:chapter-change") {
        const data = ev.data as ScriptChapterChangeEvent;
        hooks.onChapter?.({ code: data.code, name: data.name });
      } else if (ev.event === "script:progress") {
        const data = ev.data as ScriptProgressEvent;
        hooks.onProgress?.(
          data.lineCount > 0 ? Math.min(100, (data.eventIndex / data.lineCount) * 100) : 0,
        );
      } else if (ev.event === "script:player") {
        // 剧本写死的玩家台词：按事件序插进队列，占一屏（原版 PlayerProcessor 同语义）
        const data = ev.data as ScriptPlayerPayload;
        if (!data?.text) continue;
        buffered.push({
          role: "user",
          player: true,
          text: data.text,
          displayName: data.displayName || undefined,
          final: true,
        });
      } else if (ev.event === "script:background") {
        const data = ev.data as ScriptBackgroundPayload;
        roomStore.backgroundUrl = data.imagePath ?? "";
        roomStore.backgroundTransition = Number.isFinite(data.transition) ? data.transition : 0;
      } else if (ev.event === "script:background-effect") {
        // 有对应粒子组件就换层；没有对应物时 GameBackground 只记日志、留空层
        const data = ev.data as ScriptBackgroundEffectPayload;
        roomStore.backgroundEffect = data.effect ?? "";
      } else if (ev.event === "script:music") {
        const data = ev.data as ScriptMusicPayload;
        roomStore.bgm = {
          src: data.musicPath ?? "",
          playing: true,
          rate: data.playbackSpeed ?? 1,
        };
      } else if (ev.event === "script:sound") {
        const data = ev.data as ScriptSoundPayload;
        roomStore.sound = { src: data.soundPath ?? "", token: roomStore.sound.token + 1 };
      } else if (ev.event === "script:ambient") {
        const data = ev.data as ScriptAmbientPayload;
        if (data.stop) {
          // 停止指令：ambientPath 为空 = 全部停，否则只停该轨
          roomStore.ambient = data.ambientPath
            ? roomStore.ambient.filter((track) => track.src !== data.ambientPath)
            : [];
        } else if (data.ambientPath) {
          // 同一路径再次下发视为调音量/循环，不叠新轨
          const track = {
            src: data.ambientPath,
            volume: data.volume ?? 100,
            loop: data.loop ?? true,
            fade: data.fade ?? true,
          };
          roomStore.ambient = [
            ...roomStore.ambient.filter((t) => t.src !== track.src),
            track,
          ];
        }
      } else if (ev.event === "script:present-pic") {
        const data = ev.data as ScriptPresentPicPayload;
        roomStore.presentPic = data.imagePath ?? "";
        roomStore.presentPicScale = data.scale ?? 1;
      } else if (ev.event === "script:choice") {
        // 原版 ChoiceProcessor：存下选项 + 状态切回 input 等玩家点选。
        // 选项出场时机也照原版事件队列的顺序——同一批台词还没读完就先挂起，
        // 回到 input 态时再由 applyPendingChoice() 放出（见下方 state watch）。
        pendingChoice = ev.data as ScriptChoicePayload;
        // choice 也是阻塞点：后端发完事件即停轮，等 submit 续跑
        scriptBlocked = true;
        hooks.onBlocked?.(true);
      } else if (ev.event === "script:input") {
        const data = ev.data as ScriptInputEvent;
        scriptBlocked = true;
        hooks.onBlocked?.(true);
        roomStore.inputHint = data.hint;
      } else if (ev.event === "script:end") {
        ended = true;
        hooks.onEnd?.();
      } else if (ev.event === "ai:error") {
        hooks.onError?.(ev.data);
      }
      // ai:thinking / status:reset 只表示思考与流结束，播放逻辑统一在流结束后处理
    }
  } catch (error) {
    console.warn("[interview] 剧本推进失败", error);
    // 流中断不能把界面永远停在「思考中」：交回输入态并透出错误，
    // 同时视为后端已接管，避免回落本地假回复把剧本场次演成 mock 数据
    roomStore.state = "input";
    hooks.onError?.(error);
    if (!buffered.length) return true;
  }

  if (buffered.length) {
    for (const line of buffered) {
      // script:player 是剧本写死的玩家台词，历史里同样记成用户侧
      hooks.recordLine?.(line);
    }
    const first = roomStore.lines.length;
    buffered.forEach((line) => pushLine(line));
    roomStore.cursor = first;
    roomStore.state = "responding";
    return true;
  }

  applyPendingChoice();
  if (scriptBlocked) roomStore.state = "input";
  // script:end 且没有新台词时也要切回 input：房间靠这个状态触发自动收尾，
  // 否则最后一句用户输入提交后会一直停在「思考中」
  if (ended) roomStore.state = "input";
  // 没有新台词但引擎已停在阻塞点 / 已结束，同样算后端接管
  return scriptBlocked || ended;
}
