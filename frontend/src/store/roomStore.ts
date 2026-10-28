import { reactive } from "vue";

/**
 * 房间 UI store（替代原版 uiStore 里被 GameDialog 读取的房间字段）。
 * 与原版一致：对话框 / 标题栏 / 立绘都从这里取展示态；
 * 业务数据仍在 store/appStore.ts 的 session 里，本 store 只管「当前这一句」。
 */

export interface RoomLine {
  role: "ai" | "user";
  /** 已剥离【情绪】（）动作 的台词 */
  text: string;
  /** （动作）文本，灰字，独立打字机 */
  motion?: string;
  /** 【情绪】，对应 public/avatars/{code}/{emotion}.webp */
  emotion?: string;
  /** 本句是否为一段回复的收尾句 */
  final: boolean;
  /** 旁白句（剧本 script:narration）：无角色名与情绪，灰字斜体居中 */
  narration?: boolean;
  /** 玩家展示行（剧本 script:player）：由剧本写死的玩家台词，走对话区显示 */
  player?: boolean;
  /** 该句的说话人（script:player 的 displayName），缺省用房间标题 */
  displayName?: string;
  /** 该句的说话角色 code（由 ai:reply 的 senderRoleId 解析），旁白/玩家句为空 */
  speaker?: string;
  /**
   * 后端 `ai:reply` 的 `seq`（本句落库序号），`ai:voice` 靠它对句。
   * 剧本链路与本地假回复没有这个号，故可空。
   */
  seq?: number;
  /**
   * 后端 `ai:voice` 给过来的音频地址（`GET /api/speech/audio/{name}`）。
   * 合成失败 / 未开启 TTS / 剧本链路都为空——空就是本句不出声。
   */
  voiceUrl?: string;
}

/** 剧本选项（script:choice → ChoiceItem）：disabled 项灰显，reason 是作者写的锁定提示。 */
export interface RoomChoice {
  text: string;
  disabled: boolean;
  reason?: string;
}

/** 环境音轨（script:ambient，与 BGM 共存、多轨并行）。 */
export interface AmbientTrack {
  src: string;
  /** 0-100（后端字段口径），播放时折算成 0-1 */
  volume: number;
  loop: boolean;
  fade: boolean;
}

/** 对话状态机：输入 → 思考 → 回应（单句推进），与原版 input/responding 一致 */
export type RoomState = "input" | "thinking" | "responding";

const NARROW_QUERY = "(max-width: 768px)";

export const roomStore = reactive({
  showCharacterTitle: "", // 角色名
  showCharacterSubtitle: "", // 副标题（职位）
  showCharacterEmotion: "正常", // 情绪标签文案
  isNarrowScreen: false, // window.innerWidth < 768
  containerWidth: 70, // 对话框内容宽度百分比
  lines: [] as RoomLine[], // 已产出的句子队列
  cursor: 0, // 当前显示到第几句
  state: "input" as RoomState,
  inputHint: "", // 剧本 script:input 的提示语，空串表示用默认 placeholder

  // ───── 角色槽位（多角色舞台）：顺序 = 舞台从左到右，对应原版 game_status.present_role_ids ─────
  presentRoles: [] as string[], // 在场角色 code 列表，空数组 = 台上没人
  activeSpeaker: null as string | null, // 当前说话的角色 code，null = 旁白 / 玩家 / 未开始
  roleEmotions: {} as Record<string, string>, // 每个角色最近一次的情绪（原版 gameRoles[id].emotion）
  /**
   * 情绪版本号（原版 gameRoles[id].emotionRevision）：每次给角色设情绪都自增，
   * 连续两次设成同一个情绪时 roleEmotions 不变、组件不会有任何响应式变化，
   * 靠它才能让立绘动画重播一遍。
   */
  roleEmotionRevisions: {} as Record<string, number>,
  characterTransition: 0.5, // 角色上场/下场的过渡时长（秒），原版缺省 0.5

  // ───── 剧本舞台状态（B7 任务二）：free 模式恒为默认值，不影响现有渲染 ─────
  backgroundUrl: "", // script:background 的 imagePath（空串 = 用内置背景）
  backgroundTransition: 0, // script:background 的 transition（秒）
  backgroundEffect: "", // script:background-effect 的 effect（空串 = 无特效层）
  presentPic: "", // script:present-pic 的 imagePath（空串 = 收起浮层）
  presentPicScale: 1, // script:present-pic 的 scale
  bgm: {
    src: "", // script:music 的 musicPath
    playing: false,
    rate: 1, // playbackSpeed，缺省 1.0
  },
  sound: {
    src: "", // script:sound 的 soundPath（一次性播放）
    token: 0, // 自增令牌：同一段音效重复触发也能再播
  },
  ambient: [] as AmbientTrack[], // script:ambient 多轨环境音
  choices: [] as RoomChoice[], // script:choice 的选项（空数组 = 不显示选项层）
  allowFree: false, // script:choice 的 allowFree：true 时输入框可直接作答
  choiceDuration: null as number | null, // script:choice 的 duration（秒），null = 不限时
});

/**
 * 设置在场角色（script:modify-character 的 presentRoles → 舞台槽位）。
 * 顺序即槽位顺序，去重保序；空数组 = 台上没人（原版 hide_character 把最后一人请下台）。
 */
export function setPresentRoles(codes: readonly string[] | null | undefined): void {
  const list: string[] = [];
  for (const raw of codes ?? []) {
    const code = typeof raw === "string" ? raw.trim() : "";
    if (code && !list.includes(code)) list.push(code);
  }
  roomStore.presentRoles = list;
  if (roomStore.activeSpeaker && !list.includes(roomStore.activeSpeaker)) {
    roomStore.activeSpeaker = null;
  }
}

/**
 * 某个角色说了一句：切到它并对齐情绪。
 * 原版 getOrCreateGameRole 会为台词角色建角色，这里等价于「说话的角色必然在台上」。
 */
export function speakAs(code: string, emotion?: string): void {
  if (!code) return;
  if (!roomStore.presentRoles.includes(code)) {
    setPresentRoles([...roomStore.presentRoles, code]);
  }
  roomStore.activeSpeaker = code;
  if (emotion) {
    roomStore.roleEmotions[code] = emotion;
    // 同情绪连说两次也要重播动画：只靠 roleEmotions 的话值没变、watch 不会触发
    roomStore.roleEmotionRevisions[code] = (roomStore.roleEmotionRevisions[code] ?? 0) + 1;
  }
}

/** 追加一句；AI 句自动把 cursor 推到该句（用于挂载恢复/新一轮起播） */export function pushLine(line: RoomLine): number {
  const index = roomStore.lines.length;
  roomStore.lines.push(line);
  if (line.role === "ai") roomStore.cursor = index;
  return index;
}

/**
 * 推进到下一句台词（跳过过场的用户自己的句子）。
 * 队列耗尽 → 回到输入态并返回 false。
 *
 * 剧本 script:player 产生的玩家展示行也要占用一屏（原版 PlayerProcessor 同样是
 * 「展示 + 等用户继续」），所以 player 行不再被跳过。
 */
export function advance(): boolean {
  let next = roomStore.cursor + 1;
  while (next < roomStore.lines.length && !isShowableLine(roomStore.lines[next])) next += 1;
  if (next >= roomStore.lines.length) {
    roomStore.cursor = roomStore.lines.length;
    roomStore.state = "input";
    return false;
  }
  roomStore.cursor = next;
  roomStore.state = "responding";
  return true;
}

/** 这一句是否占一屏（AI 台词，或剧本写死的玩家台词）；用户自己发的消息只是历史记号。 */
export function isShowableLine(line: RoomLine): boolean {
  return line.role === "ai" || line.player === true;
}

export function resetRoom(): void {
  roomStore.lines = [];
  roomStore.cursor = 0;
  roomStore.state = "input";
  roomStore.inputHint = "";
  // 舞台层一并回到默认（背景 / 浮层 / 音轨 / 特效 / 选项）
  roomStore.backgroundUrl = "";
  roomStore.backgroundTransition = 0;
  roomStore.backgroundEffect = "";
  roomStore.presentPic = "";
  roomStore.presentPicScale = 1;
  roomStore.bgm = { src: "", playing: false, rate: 1 };
  roomStore.sound = { src: "", token: 0 };
  roomStore.ambient = [];
  roomStore.choices = [];
  roomStore.allowFree = false;
  roomStore.choiceDuration = null;
  // 角色槽位一并清空（进房间时由房间按人设重新填一位）
  roomStore.presentRoles = [];
  roomStore.activeSpeaker = null;
  roomStore.roleEmotions = {};
  roomStore.roleEmotionRevisions = {};
  roomStore.characterTransition = 0.5;
}

/** 视口适配：窄屏判定 + 内容宽度（原版 max(60, 宽高比>1 ? 70 : 90)） */
export function syncViewport(): void {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  roomStore.isNarrowScreen = window.matchMedia(NARROW_QUERY).matches;
  roomStore.containerWidth = Math.max(60, vw / vh > 1 ? 70 : 90);
}

/** 注册视口监听，返回注销函数 */
export function initRoomViewport(): () => void {
  syncViewport();
  window.addEventListener("resize", syncViewport);
  return () => window.removeEventListener("resize", syncViewport);
}
