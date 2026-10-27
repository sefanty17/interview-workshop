package cn.edu.zjut.interview.knowledge;

import cn.edu.zjut.interview.config.ConfigKey;
import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.dto.KnowledgeHit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * K4 的注入决策：六种情况各自该返回什么。
 *
 * <p>用 mock 而不是真起 Spring 上下文，因为这些断言的关注点是「什么条件下注入 / 不注入」，
 * 与数据库、embedding 全无关。检索质量本身由 {@code RetrievalServiceTest} 与
 * {@code scripts/eval_knowledge.py} 负责。
 */
class KnowledgeInjectorTest {

    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final ConfigService config = mock(ConfigService.class);

    private KnowledgeInjector injector() {
        return new KnowledgeInjector(retrieval, config);
    }

    private static KnowledgeHit hit(String heading, String text) {
        return new KnowledgeHit(7L, 3L, "java-jvm-memory", "Java 虚拟机内存与对象生命周期",
                heading, text, 0.0325, 0.207, 8.51);
    }

    private void stubEnabled(int topK) {
        when(config.getBoolean(ConfigKey.KNOWLEDGE_INJECT_ENABLED)).thenReturn(true);
        when(config.getInt(ConfigKey.KNOWLEDGE_INJECT_TOP_K)).thenReturn(topK);
    }

    /** 开关关闭：一次检索都不该发生（省的是每次对话一次全量打分）。 */
    @Test
    void 开关关闭时不检索() {
        when(config.getBoolean(ConfigKey.KNOWLEDGE_INJECT_ENABLED)).thenReturn(false);

        assertTrue(injector().build("内存泄漏怎么排查", "strict", "java").isEmpty());
        verifyNoInteractions(retrieval);
    }

    /** 空白输入：不检索，避免用空串去算 BM25（结果必然是 0 分却要走一遍全库）。 */
    @Test
    void 空白输入不检索() {
        when(config.getBoolean(ConfigKey.KNOWLEDGE_INJECT_ENABLED)).thenReturn(true);

        assertTrue(injector().build("   ", "strict", "java").isEmpty());
        verifyNoInteractions(retrieval);
    }

    /** 没命中（阈值全拦）：返回空注入，且 block 是空串而不是只有抬头的半成品。 */
    @Test
    void 没有命中时不注入() {
        stubEnabled(3);
        when(retrieval.search(anyString(), anyString(), anyString(), any(), any())).thenReturn(List.of());

        KnowledgeInjector.Injection injection = injector().build("今天天气怎么样", "strict", "java");

        assertTrue(injection.isEmpty());
        assertEquals("", injection.block());
    }

    /** 命中：块里必须出现「[文档标题 > 小节]」和正文，否则模型看到的是没出处的裸文本。 */
    @Test
    void 命中时渲染出小节标题与正文() {
        stubEnabled(3);
        String body = "内存泄漏排查：先用 jstat 看 GC 曲线，再用 jmap 导出堆。";
        when(retrieval.search(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(hit("Java 虚拟机内存与对象生命周期 > 内存泄漏的典型形态", body)));

        KnowledgeInjector.Injection injection = injector().build("内存泄漏怎么排查", "strict", "java");

        assertFalse(injection.isEmpty());
        assertTrue(injection.block().contains("内存泄漏的典型形态"), "块里必须带小节标题");
        assertTrue(injection.block().contains(body), "块里必须带正文");
        assertTrue(injection.block().startsWith("[岗位知识参考资料]"), "必须有抬头，声明这是资料不是指令");
        assertEquals(1, injection.hits().size());
    }

    /** topK 必须来自配置：注多了会挤掉历史上下文，这个值要能调。 */
    @Test
    void topK来自配置() {
        stubEnabled(2);
        when(retrieval.search(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(hit("小节", "正文")));

        injector().build("线程池参数", "strict", "java");

        ArgumentCaptor<RetrievalService.SearchOptions> captor =
                ArgumentCaptor.forClass(RetrievalService.SearchOptions.class);
        verify(retrieval).search(anyString(), anyString(), anyString(), captor.capture(), any());
        assertEquals(2, captor.getValue().topK());
        // 阈值与权重保持默认：注入链路不该偷偷改标定过的参数
        assertEquals(RetrievalService.SearchOptions.defaults().minBm25Score(),
                captor.getValue().minBm25Score(), 1e-9);
    }

    /** 检索抛异常（索引重建失败、库抖动）：降级成不注入，绝不能把整轮对话打挂。 */
    @Test
    void 检索异常时降级为空注入() {
        stubEnabled(3);
        when(retrieval.search(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("索引重建失败"));

        KnowledgeInjector.Injection injection = injector().build("内存泄漏怎么排查", "strict", "java");

        assertTrue(injection.isEmpty());
    }
}
