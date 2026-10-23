package cn.edu.zjut.interview.engine;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 剧本事件，对应 {@code script_engine/events/mod.rs} 的 {@code ScriptEvent} trait。
 *
 * <p>返回非 {@code null} 表示「章节结束，下一章叫这个名字」；
 * 返回 {@link EngineSignal#INPUT} / {@link EngineSignal#FREE_DIALOGUE} /
 * {@link EngineSignal#CHOICE} 表示「阻塞点，本次推进到此为止」；
 * 返回 {@code null} 表示事件已执行完，继续下一个。
 */
public interface ScriptEvent {

    String execute(EngineContext ctx) throws Exception;

    /**
     * 事件间隔（秒），对应 {@code events/mod.rs:157 parse_duration}。
     *
     * <p>只接受数字（整数也算），没写返回 {@code null}——原版注释：负数允许，
     * 由前端按「等玩家」处理，但 {@code None} 表示「没写」。
     */
    static Double parseDuration(JsonNode event) {
        if (event == null) {
            return null;
        }
        JsonNode node = event.get("duration");
        return node != null && node.isNumber() ? node.asDouble() : null;
    }
}
