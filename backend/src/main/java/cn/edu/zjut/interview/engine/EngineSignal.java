package cn.edu.zjut.interview.engine;

/**
 * 事件执行结果里的「非跳章信号」。
 *
 * <p>Rust 的 {@code ScriptEvent::execute} 返回 {@code Result<Option<String>>}，
 * 阻塞事件（input / free_dialogue / choices）靠 {@code rx.await} 挂起线程；
 * Java 没有进程内长跑上下文，因此规格 §1 把「挂起」映射成「返回一个哨兵值，
 * 让主循环 return 到 HTTP 边界」。
 *
 * <p>这些哨兵与章节名不会撞车（章节名来自 YAML 的 {@code next}，不可能以 {@code @@} 开头）。
 */
public final class EngineSignal {

    /** input 事件：已落库、已推 SSE，主循环立即返回。 */
    public static final String INPUT = "@@engine:input@@";

    /** free_dialogue 事件：已把 freeDialogue 写进变量表，主循环立即返回。 */
    public static final String FREE_DIALOGUE = "@@engine:free_dialogue@@";

    /**
     * choices 事件：已推 {@code script:choice} 并把 {@code pendingChoice} 写进变量表，
     * 主循环立即返回（原版是 {@code choice_tx} oneshot 挂起）。
     */
    public static final String CHOICE = "@@engine:choice@@";

    private EngineSignal() {
    }

    /** 是否为阻塞信号：主循环见到就 return，用户下次请求再续跑。 */
    public static boolean isBlocking(String result) {
        return INPUT.equals(result) || FREE_DIALOGUE.equals(result) || CHOICE.equals(result);
    }
}
