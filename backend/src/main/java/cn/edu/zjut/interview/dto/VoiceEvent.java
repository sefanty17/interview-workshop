package cn.edu.zjut.interview.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code ai:voice} 事件的 data —— <b>本项目扩展</b>，上游没有这个事件。
 *
 * <p>上游（Rust 版）的做法是把音频文件路径挂在台词行上（{@code voice_file} 字段），
 * 前端拿到台词时顺带拿到音频。这里<b>不能照抄</b>，原因见规格 V6a §0：
 * 合成是异步的（一次 HTTP 请求动辄几百毫秒），而 {@code ai:reply} 必须在首字到达时
 * 立刻发出，事件组装的那一刻 URL 还不存在，字段根本填不进去。所以补发一个独立事件：
 * <ul>
 *   <li>{@code ai:reply} 的时序完全不变，TTS 慢 / 挂掉都不影响对话；</li>
 *   <li>语音合成完再单独推一条，前端按 {@code seq} 把它对上已经显示出来的那句台词，
 *       排在队里播（播放队列本身是 V6b 的事，本批只负责把 URL 送到）。</li>
 * </ul>
 *
 * @param type     固定 {@code "voice"}，与 {@link cn.edu.zjut.interview.dto.ReplyEvent#TYPE} 同构
 * @param seq      对应台词的 seq（= 该句 {@code ai:reply} 落库用的那个 seq），前端靠它对句
 * @param voiceUrl 音频地址，形如 {@code /api/speech/audio/<sha1>.wav}；内容不可变，可长期缓存
 */
@JsonPropertyOrder({"type", "seq", "voiceUrl"})
public record VoiceEvent(String type, Integer seq, String voiceUrl) {

    /** data 里的 type 字段取值，规格 V6a §3。 */
    public static final String TYPE = "voice";

    /** SSE 事件名，规格 V6a §0 第 1 条。 */
    public static final String EVENT = "ai:voice";
}
