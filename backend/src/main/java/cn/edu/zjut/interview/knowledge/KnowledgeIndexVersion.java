package cn.edu.zjut.interview.knowledge;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 知识库内容的版本号：写入 / 删除 / 重建之后加一，检索侧据此判断缓存是否过期。
 *
 * <p>单独抽出来的原因：{@link RetrievalService} 若直接依赖 {@code KnowledgeService} 会形成循环依赖
 * （写路径需要 embedding、读路径需要写入后的失效通知）。两边各自注入这个轻量组件即可，
 * 谁也不用认识谁。
 *
 * <p>只关心「内容有没有变」，不关心谁改的：角色挂载（{@code setDocCodesOf}）<b>不</b>加版本，
 * 因为索引内容没变，检索范围是每次查询时现算的。
 */
@Component
public class KnowledgeIndexVersion {

    /** 初值 1 而不是 0：0 用来表示「本地还没有快照」，语义更清楚。 */
    private final AtomicLong version = new AtomicLong(1);

    public long current() {
        return version.get();
    }

    /** 版本加一并返回新值。 */
    public long bump() {
        return version.incrementAndGet();
    }
}
