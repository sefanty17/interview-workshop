/**
 * 房间台词的语音播放：单一 `<audio>` 元素复用，切句时 stop 再 play。
 *
 * `voiceUrl` 是后端 `/api/speech/audio/<指纹>.<ext>`，指纹由「文本 + 情绪 + 音色」算出，
 * 内容不可变、可缓存——同一句重播会命中后端缓存、拿到同一个 URL，不会重复合成。
 *
 * 只做「有声叠加」：播不播都不影响房间推进节奏，合成失败 / 浏览器拒绝自动播放时
 * 表现只是「这一句没声音」，页面不能因此报错。
 */

export interface VoicePlayback {
  /** 播放一段语音；已在播的先停掉（同一时刻只有一句的声音） */
  play(url: string): void;
  /** 停止并回到开头 */
  stop(): void;
  /** 组件卸载时释放（断开 src，让浏览器回收解码资源） */
  dispose(): void;
}

export function useVoicePlayback(): VoicePlayback {
  // 懒建：模块被 import 时不碰 DOM（测试环境 / 首屏都没必要先建一个 audio）
  let el: HTMLAudioElement | null = null;

  function element(): HTMLAudioElement {
    if (!el) {
      el = new Audio();
      el.preload = "auto";
    }
    return el;
  }

  function stop(): void {
    if (!el) return;
    el.pause();
    el.currentTime = 0;
  }

  return {
    play(url: string): void {
      stop();
      const audio = element();
      audio.src = url;
      // 自动播放策略 / 404 / 解码失败都会 reject，语音出不来绝不能炸页面
      void audio.play().catch((error: unknown) => {
        console.warn("[voice] 播放失败", url, error);
      });
    },
    stop,
    dispose(): void {
      if (!el) return;
      stop();
      el.src = "";
      el = null;
    },
  };
}
