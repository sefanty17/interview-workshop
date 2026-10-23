package cn.edu.zjut.interview.engine;

/**
 * 剧本输入被拒：对应原版 {@code script_submit_input} 返回 {@code Err} 的两条路径
 * （{@code script.rs:231-236} 选项挂起却不接受自由输入、{@code :244} 当前没有等待输入的事件）。
 *
 * <p>两条都是「拒绝且不消费」——用户消息不落库、事件下标不推进、挂起状态保持。
 * Controller 捕获后以 SSE {@code ai:error} 下发，语义等同原版把 Err 回给前端。
 */
public class ScriptInputRejectedException extends RuntimeException {

    public ScriptInputRejectedException(String message) {
        super(message);
    }
}
