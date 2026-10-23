package cn.edu.zjut.interview.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 未注册事件的占位实现（规格 §3.6 最后一行、B7 禁令第 3 条）。
 *
 * <p>Rust 侧 {@code create_event} 对未知类型返回 {@code None}，由 {@code events_handler.rs:109}
 * 抛「未注册的事件类型」。本项目按规格改为「记 warn 后继续」：规格 B7 落完 9 个事件类型后，
 * 本批唯一不迁的是 {@code achievement}（成就属桌面端运行时，2026-10-20 口径），
 * 遇到它走这里记 warn 不算错；将来剧本里出现新的未知类型也不会整场中断。
 */
public class NoopEvent implements ScriptEvent {

    private static final Logger log = LoggerFactory.getLogger(NoopEvent.class);

    private final String type;

    public NoopEvent(String type) {
        this.type = type;
    }

    @Override
    public String execute(EngineContext ctx) {
        log.warn("[ScriptEngine] 未注册事件类型: '{}'", type);
        return null;
    }
}
