package cn.edu.zjut.interview.knowledge;

import cn.edu.zjut.interview.dto.ChunkCommand;
import cn.edu.zjut.interview.dto.CreateDocCommand;
import cn.edu.zjut.interview.dto.KnowledgeDocView;
import cn.edu.zjut.interview.dto.KnowledgeHit;
import cn.edu.zjut.interview.service.KnowledgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 K3 §7 的七条断言：分词、BM25、向量、融合、阈值、范围、版本失效；
 * 外加 K6 第八条：假设问题（HyPE）只进词表、不进结果正文。
 *
 * <p>自己造 3 篇文档 6 个片段（前缀 {@code k3-}），与 {@code KnowledgeServiceTest} 同一风格：
 * {@code @SpringBootTest} + test profile 的内存库，{@code @AfterEach} 按前缀删干净，
 * 挂载统一挂在种子角色 {@code strict} 上、用完清空。
 *
 * <p>断言之间刻意不共享状态：每条用例都从 {@link #准备语料()} 的全新语料开始，
 * 免得「上一条缓存过索引」影响下一条。
 */
@SpringBootTest
@ActiveProfiles("test")
class RetrievalServiceTest {

    private static final String ROLE = "strict";
    private static final String PREFIX = "k3-";
    private static final String JVM_DOC = PREFIX + "jvm-memory";
    private static final String DB_DOC = PREFIX + "db-tx";
    private static final String FE_DOC = PREFIX + "frontend-vue";

    /** 讲垃圾回收的片段，BM25 用例的正样本。 */
    private static final String GC_TEXT = "垃圾回收器有 G1 与 ZGC，分代回收的停顿时间取决于堆大小与回收算法";
    /** 讲内存排查的片段，向量用例的正样本。 */
    private static final String LEAK_TEXT = "内存泄漏怎么定位：先用 jmap 抓堆转储，再用 MAT 看持有链，静态集合没清理是最常见的原因";
    /** 讲索引失效的片段，挂载范围用例的正样本。 */
    private static final String INDEX_TEXT = "索引失效常见于函数包裹列、隐式类型转换与前缀模糊查询";
    /** 讲前端拆包的片段，岗位范围用例的正样本。 */
    private static final String BUNDLE_TEXT = "打包优化按路由拆包，配合 tree shaking 去掉未使用的导出";
    /** 版本失效用例新建的片段正文。 */
    private static final String PROBE_TEXT = "版本缓存失效探针只在这一条片段里出现";

    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private KnowledgeService knowledgeService;

    @BeforeEach
    void 准备语料() {
        knowledgeService.createDoc(new CreateDocCommand(JVM_DOC, "JVM 内存与垃圾回收", "java", null, null));
        knowledgeService.syncChunks(JVM_DOC, List.of(
                new ChunkCommand("第2章 > 2.1 垃圾回收器", GC_TEXT),
                new ChunkCommand("第2章 > 2.2 内存泄漏排查", LEAK_TEXT)));

        knowledgeService.createDoc(new CreateDocCommand(DB_DOC, "数据库事务与索引", "java", null, null));
        knowledgeService.syncChunks(DB_DOC, List.of(
                new ChunkCommand("第3章 > 3.1 事务隔离", "事务隔离级别有读已提交与可重复读，间隙锁用来避免幻读"),
                new ChunkCommand("第3章 > 3.2 索引失效", INDEX_TEXT)));

        knowledgeService.createDoc(new CreateDocCommand(FE_DOC, "前端工程化", "frontend", null, null));
        knowledgeService.syncChunks(FE_DOC, List.of(
                new ChunkCommand("第1章 > 1.1 响应式原理", "Vue 响应式用 Proxy 拦截对象读写，依赖收集发生在渲染函数执行时"),
                new ChunkCommand("第1章 > 1.2 打包优化", BUNDLE_TEXT)));
    }

    @AfterEach
    void 清理用例数据() {
        knowledgeService.setDocCodesOf(ROLE, List.of());
        for (KnowledgeDocView view : knowledgeService.listDocs()) {
            if (view.code().startsWith(PREFIX)) {
                try {
                    knowledgeService.deleteDoc(view.code());
                } catch (RuntimeException ignored) {
                    // 用例中途已删或不存在，兜底忽略
                }
            }
        }
    }

    /** 1. 分词：拉丁词整段切出，中文按相邻两字切。 */
    @Test
    void 分词切出拉丁词与中文二元组() {
        List<String> mixed = ChineseTokenizer.tokenize("JVM内存泄漏怎么排查");
        assertTrue(mixed.containsAll(List.of("jvm", "内存", "泄漏", "排查")),
                "实际切分：" + mixed);

        List<String> latin = ChineseTokenizer.tokenize("Spring Boot 的 @Transactional");
        assertTrue(latin.containsAll(List.of("spring", "boot")), "实际切分：" + latin);

        // 空白与 null 一律给空表；不去重，BM25 要靠重复项统计词频
        assertTrue(ChineseTokenizer.tokenize("   ").isEmpty());
        assertTrue(ChineseTokenizer.tokenize(null).isEmpty());
        assertEquals(List.of("gc", "gc"), ChineseTokenizer.tokenize("GC GC"));
        // 单字段没有相邻字可组，按原字输出
        assertEquals(List.of("回", "收"), ChineseTokenizer.tokenize("回 收"));
    }

    /** 2. BM25 排序：讲 GC 的片段排第一，且原始分大于 0。 */
    @Test
    void BM25把讲垃圾回收的片段排在第一() {
        List<KnowledgeHit> hits = retrievalService.search("垃圾回收器", null, null,
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.BM25_ONLY);

        assertFalse(hits.isEmpty(), "BM25 路不应完全空手");
        KnowledgeHit top = hits.get(0);
        assertEquals(JVM_DOC, top.docCode());
        assertEquals(GC_TEXT, top.text());
        assertTrue(top.bm25Score() > 0.0, "BM25 原始分必须为正：" + top.bm25Score());
        // 只开 BM25 时向量路没跑，向量分恒为 0
        assertEquals(0.0, top.vectorScore());
    }

    /** 3. 向量排序：只开向量路时讲内存排查的片段排第一。 */
    @Test
    void 向量路把讲内存泄漏的片段排在第一() {
        List<KnowledgeHit> hits = retrievalService.search("内存泄漏怎么定位", null, null,
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.VECTOR_ONLY);

        assertFalse(hits.isEmpty(), "向量路不应完全空手");
        KnowledgeHit top = hits.get(0);
        assertEquals(JVM_DOC, top.docCode());
        assertEquals(LEAK_TEXT, top.text());
        assertTrue(top.vectorScore() > 0.0, "余弦必须为正：" + top.vectorScore());
        assertEquals(0.0, top.bm25Score());
    }

    /** 4. 混合不劣于单路：第一条同时出现在两路候选里（两路原始分都大于 0）。 */
    @Test
    void 混合检索第一条同时被两路召回() {
        List<KnowledgeHit> hits = retrievalService.search("内存泄漏怎么定位", null, null,
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.HYBRID);

        assertFalse(hits.isEmpty());
        KnowledgeHit top = hits.get(0);
        assertTrue(top.vectorScore() > 0.0, "向量路没召回：" + top);
        assertTrue(top.bm25Score() > 0.0, "BM25 路没召回：" + top);
        assertEquals(LEAK_TEXT, top.text());
        // RRF 是两个 1/(60+rank) 的和，第一名的融合分必然大于单路贡献
        assertTrue(top.score() > 0.0);
    }

    /** 5. 阈值：不相关查询两路都不过阈值 => 整条丢掉，返回空列表（K4「不注入」的依据）。 */
    @Test
    void 不相关查询被阈值逐条过滤成空列表() {
        assertTrue(retrievalService.search("今天天气怎么样", null, null,
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.HYBRID).isEmpty());

        // 单路也不能漏：阈值是逐条判定，不是只看排名第一的那条
        assertTrue(retrievalService.search("今天天气怎么样", null, null,
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.BM25_ONLY).isEmpty());
        assertTrue(retrievalService.search("今天天气怎么样", null, null,
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.VECTOR_ONLY).isEmpty());
    }

    /** 6. 范围：岗位过滤 + 挂载优先（挂载了就不再按岗位捞别的文档）。 */
    @Test
    void 检索范围先挂载后岗位() {
        // 岗位过滤：frontend 只能出现前端那篇
        List<KnowledgeHit> frontend = retrievalService.search("打包优化按路由拆包", null, "frontend",
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.HYBRID);
        assertFalse(frontend.isEmpty(), "frontend 岗位应能命中拆包那段");
        assertTrue(frontend.stream().allMatch(hit -> FE_DOC.equals(hit.docCode())),
                "出现了非前端文档的片段：" + frontend);
        assertEquals(2, retrievalService.scopeSize(null, "frontend"));

        // 挂载优先：strict 只挂数据库那篇，即使同时传了 jobCode=java 也不该捞到 JVM 那篇
        knowledgeService.setDocCodesOf(ROLE, List.of(DB_DOC));
        assertEquals(2, retrievalService.scopeSize(ROLE, "java"));
        assertTrue(retrievalService.search("垃圾回收器", ROLE, "java",
                        RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.HYBRID).isEmpty(),
                "挂载范围外的文档不能被检索到");

        List<KnowledgeHit> mounted = retrievalService.search("索引失效常见于函数包裹列", ROLE, "java",
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.HYBRID);
        assertFalse(mounted.isEmpty());
        assertTrue(mounted.stream().allMatch(hit -> DB_DOC.equals(hit.docCode())),
                "结果只应来自挂载的那一篇：" + mounted);
    }

    /** 7. 版本失效：先查一次把索引缓存住，写入新片段后必须能立刻检索到。 */
    @Test
    void 写入新片段后缓存立即失效() {
        assertTrue(retrievalService.search("版本缓存失效探针", null, null,
                        RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.HYBRID).isEmpty(),
                "新建之前不该有命中，先触发一次索引构建");

        String probeDoc = PREFIX + "version-probe";
        knowledgeService.createDoc(new CreateDocCommand(probeDoc, "版本探针", "java", null, null));
        knowledgeService.syncChunks(probeDoc, List.of(new ChunkCommand("第1章", PROBE_TEXT)));

        List<KnowledgeHit> hits = retrievalService.search("版本缓存失效探针", null, null,
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.HYBRID);
        assertFalse(hits.isEmpty(), "写入后索引缓存没失效，新片段检索不到");
        assertTrue(hits.stream().anyMatch(hit -> PROBE_TEXT.equals(hit.text())),
                "新片段必须出现在结果里：" + hits);
    }

    /**
     * 8. HyPE：假设问题只进词表、不进结果正文。
     *
     * <p>构造刻意选「正文与问法零重合」的一对：正文讲三色标记与增量更新，
     * 用户问的是「写屏障」。不带假设问题时这条必然检索不到（证明差距真实存在，
     * 不是碰巧命中），补上假设问题后同一句话就能命中（证明 HyPE 起了作用）。
     * 最后核对结果里的 text 仍是正文原文 —— 假设问题是模型生成的问题，
     * 一旦混进 text 会被拼进提示词，面试官就会照着念一句谁也没说过的问题。
     */
    @Test
    void 假设问题让用户自己的说法能命中且不污染结果正文() {
        String doc = PREFIX + "hype-probe";
        String text = "并发标记阶段用三色标记法，漏标由增量更新与原始快照修正";
        String heading = "第9章 > 9.1 三色标记";
        String query = "写屏障是干什么的";
        String hype = "写屏障是干什么的？\n三色标记的漏标问题怎么解决？";
        knowledgeService.createDoc(new CreateDocCommand(doc, "并发标记", "java", null, null));

        // 先只写正文：正文里没有「写屏障」这三个字，这条问法不该命中它
        knowledgeService.syncChunks(doc, List.of(new ChunkCommand(heading, text)));
        assertTrue(retrievalService.search(query, null, null,
                        RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.BM25_ONLY)
                        .stream().noneMatch(hit -> text.equals(hit.text())),
                "没有假设问题时不该命中，否则这条用例证明不了什么");

        // 补上假设问题：用户自己的说法被提前写进索引，同一句话就该命中
        knowledgeService.syncChunks(doc, List.of(new ChunkCommand(heading, text, hype)));
        List<KnowledgeHit> hits = retrievalService.search(query, null, null,
                RetrievalService.SearchOptions.defaults(), RetrievalService.RetrievalMode.BM25_ONLY);
        assertTrue(hits.stream().anyMatch(hit -> text.equals(hit.text())),
                "假设问题没有进索引，「写屏障」这类问法检索不到：" + hits);

        // 结果正文必须是原文：假设问题只在词表里起作用
        assertTrue(hits.stream().noneMatch(hit -> hit.text().contains("写屏障是干什么的")),
                "假设问题漏进了结果正文：" + hits);
    }
}
