package cn.edu.zjut.interview.knowledge;

import cn.edu.zjut.interview.domain.KnowledgeDoc;
import cn.edu.zjut.interview.dto.ChunkCommand;
import cn.edu.zjut.interview.dto.CreateDocCommand;
import cn.edu.zjut.interview.dto.KnowledgeDocView;
import cn.edu.zjut.interview.repository.InterviewerKnowledgeRepository;
import cn.edu.zjut.interview.repository.KnowledgeChunkRepository;
import cn.edu.zjut.interview.repository.KnowledgeDocRepository;
import cn.edu.zjut.interview.repository.KnowledgeVectorRepository;
import cn.edu.zjut.interview.service.KnowledgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 K1 §8 的 5 条断言：文档 CRUD、覆盖式写入、挂载覆盖、级联删除。
 *
 * <p>用 {@code @SpringBootTest} + test profile（内存库，不碰开发库），
 * 与 {@code InterviewerRelationService} 那类库表测试同一风格：
 * 事务边界由 Service 自己的 {@code @Transactional} 控制，测试方法不额外开事务。
 *
 * <p>用例建的文档都带 {@link #PREFIX}，{@code @AfterEach} 兜底删干净；
 * 挂载统一挂在种子角色 {@code strict} 上，用完清空。
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeServiceTest {

    private static final String ROLE = "strict";
    private static final String PREFIX = "k1-test-";

    @Autowired
    private KnowledgeService service;
    @Autowired
    private KnowledgeDocRepository docRepository;
    @Autowired
    private KnowledgeChunkRepository chunkRepository;
    @Autowired
    private KnowledgeVectorRepository vectorRepository;
    @Autowired
    private InterviewerKnowledgeRepository mountRepository;

    @AfterEach
    void 清理用例数据() {
        service.setDocCodesOf(ROLE, List.of());
        for (KnowledgeDocView view : service.listDocs()) {
            if (view.code().startsWith(PREFIX)) {
                try {
                    service.deleteDoc(view.code());
                } catch (RuntimeException ignored) {
                    // 用例自己已经删掉了，兜底忽略
                }
            }
        }
    }

    /** 5. createDoc 后 listDocs 能查到，且片段/向量都是 0。 */
    @Test
    void 建文档后列表可见且片段为零() {
        String code = PREFIX + "create";
        KnowledgeDocView created = service.createDoc(
                new CreateDocCommand(code, "JVM 调优", "后端", "docs/jvm.md", "内存与 GC"));

        assertEquals(code, created.code());
        assertTrue(created.enabled());
        assertEquals(0, created.chunkCount());
        assertEquals(0, created.vectorCount());

        KnowledgeDocView listed = viewOf(code);
        assertEquals("JVM 调优", listed.title());
        assertEquals("后端", listed.category());
        assertEquals("docs/jvm.md", listed.sourcePath());
        assertEquals(0, listed.chunkCount());
        assertEquals(0, listed.vectorCount());
        assertNotNull(listed.updatedAt());
    }

    /** 6. 写 3 条片段 => chunkCount/vectorCount 都是 3，维度与模型来自 hashing 实现。 */
    @Test
    void 写三条片段后同时生成三条向量() {
        String code = PREFIX + "chunks";
        service.createDoc(new CreateDocCommand(code, "JVM 调优", "后端", null, null));

        int count = service.replaceChunks(code, List.of(
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第1章 > 1.1", "垃圾回收器有 G1 和 ZGC"),
                new ChunkCommand("第2章", "内存泄漏用 jmap 和 MAT 排查")));

        assertEquals(3, count);
        KnowledgeDocView view = viewOf(code);
        assertEquals(3, view.chunkCount());
        assertEquals(3, view.vectorCount());
        assertEquals(512, view.dim());
        assertEquals("hashing-512", view.model());
    }

    /** 7. 再覆盖写 2 条 => 旧的第 3 条（含它的向量）必须被清掉，不能残留。 */
    @Test
    void 再次覆盖写入时旧向量不残留() {
        String code = PREFIX + "rechunk";
        service.createDoc(new CreateDocCommand(code, "JVM 调优", "后端", null, null));
        service.replaceChunks(code, List.of(
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第1章 > 1.1", "垃圾回收器有 G1 和 ZGC"),
                new ChunkCommand("第2章", "内存泄漏用 jmap 和 MAT 排查")));

        int count = service.replaceChunks(code, List.of(
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第3章", "类加载过程分五个阶段")));

        assertEquals(2, count);
        KnowledgeDocView view = viewOf(code);
        assertEquals(2, view.chunkCount());
        assertEquals(2, view.vectorCount());

        KnowledgeDoc doc = docRepository.findByCode(code).orElseThrow();
        assertEquals(2, chunkRepository.findByDocIdOrderByChunkIndexAsc(doc.getId()).size());
        assertEquals(2, vectorRepository.findByDocId(doc.getId()).size());
    }

    /** 8. 挂载是整份覆盖：先 2 条，再传 1 条，结果就是 1 条而不是 3 条。 */
    @Test
    void 角色挂载是覆盖式而非追加() {
        String codeA = PREFIX + "mount-a";
        String codeB = PREFIX + "mount-b";
        service.createDoc(new CreateDocCommand(codeA, "文档 A", "后端", null, null));
        service.createDoc(new CreateDocCommand(codeB, "文档 B", "后端", null, null));

        service.setDocCodesOf(ROLE, List.of(codeA, codeB));
        List<String> first = service.docCodesOf(ROLE);
        assertEquals(2, first.size());
        assertTrue(first.containsAll(List.of(codeA, codeB)));

        service.setDocCodesOf(ROLE, List.of(codeB));
        List<String> second = service.docCodesOf(ROLE);
        assertEquals(1, second.size());
        assertEquals(codeB, second.get(0));
    }

    /** 9. 删文档后，它的片段、向量、角色挂载一条都不能剩。 */
    @Test
    void 删文档时级联清空片段向量与挂载() {
        String code = PREFIX + "delete";
        service.createDoc(new CreateDocCommand(code, "待删文档", "后端", null, null));
        service.replaceChunks(code, List.of(new ChunkCommand("第1章", "一段正文")));
        service.setDocCodesOf(ROLE, List.of(code));

        KnowledgeDoc doc = docRepository.findByCode(code).orElseThrow();
        Long docId = doc.getId();

        service.deleteDoc(code);

        assertFalse(docRepository.existsByCode(code));
        assertEquals(0, chunkRepository.countByDocId(docId));
        assertTrue(vectorRepository.findByDocId(docId).isEmpty());
        // 挂载表没有 findByDocId（仓库签名按规格写死），用全表扫描确认没有指向该文档的孤儿行
        assertTrue(mountRepository.findAll().stream().noneMatch(mount -> docId.equals(mount.getDocId())));
        assertTrue(service.docCodesOf(ROLE).isEmpty());
    }

    private KnowledgeDocView viewOf(String code) {
        return service.listDocs().stream()
                .filter(view -> view.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("文档没出现在 listDocs 里：" + code));
    }

    // ---------------------------------------------------------------- K2 增量同步
    //
    // 四条断言的共同点：不看「条数对不对」，看「几条被保留、几条被重建」。
    // chunk id 变了就说明那一行被删了重插，也就意味着向量重算过。

    /** K2-1. 同一份片段连写两次：第二次必须整篇复用，一条都不重算。 */
    @Test
    void 重复写入同一份片段时零重建() {
        String code = PREFIX + "sync-same";
        service.createDoc(new CreateDocCommand(code, "幂等", "java", null, null));
        List<ChunkCommand> chunks = List.of(
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第1章 > 1.1", "垃圾回收器有 G1 和 ZGC"),
                new ChunkCommand("第2章", "内存泄漏用 jmap 和 MAT 排查"));

        service.syncChunks(code, chunks);
        List<Long> idsBefore = chunkIds(code);

        KnowledgeService.ChunkSyncResult result = service.syncChunks(code, chunks);

        assertEquals(3, result.total());
        assertEquals(3, result.reused());
        assertEquals(0, result.inserted());
        assertEquals(0, result.deleted());
        assertEquals(0, result.reembedded());
        assertEquals(idsBefore, chunkIds(code), "复用必须保留原 chunk id");
    }

    /** K2-2. 改一个字：只重建那一条，其余两条 id 不变。 */
    @Test
    void 改动一条正文时只重建那一条() {
        String code = PREFIX + "sync-one";
        service.createDoc(new CreateDocCommand(code, "增量", "java", null, null));
        service.syncChunks(code, List.of(
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第1章 > 1.1", "垃圾回收器有 G1 和 ZGC"),
                new ChunkCommand("第2章", "内存泄漏用 jmap 和 MAT 排查")));
        List<Long> idsBefore = chunkIds(code);

        KnowledgeService.ChunkSyncResult result = service.syncChunks(code, List.of(
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第1章 > 1.1", "垃圾回收器有 G1 和 ZGC2"),
                new ChunkCommand("第2章", "内存泄漏用 jmap 和 MAT 排查")));

        assertEquals(3, result.total());
        assertEquals(2, result.reused());
        assertEquals(1, result.inserted());
        assertEquals(1, result.deleted());
        List<Long> idsAfter = chunkIds(code);
        assertEquals(3, idsAfter.size());
        assertTrue(idsAfter.contains(idsBefore.get(0)), "第 1 条应被复用");
        assertTrue(idsAfter.contains(idsBefore.get(2)), "第 3 条应被复用");
        assertFalse(idsAfter.contains(idsBefore.get(1)), "改过的那条必须是新行");
    }

    /** K2-3. 在开头插一段：按指纹配对，后面三条的位置整体后移但 id 全部保留。 */
    @Test
    void 开头插入一段时其余片段不被误重建() {
        String code = PREFIX + "sync-insert";
        service.createDoc(new CreateDocCommand(code, "插入", "java", null, null));
        service.syncChunks(code, List.of(
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第1章 > 1.1", "垃圾回收器有 G1 和 ZGC")));
        List<Long> idsBefore = chunkIds(code);

        KnowledgeService.ChunkSyncResult result = service.syncChunks(code, List.of(
                new ChunkCommand("第0章", "这一段是新插到最前面的"),
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第1章 > 1.1", "垃圾回收器有 G1 和 ZGC")));

        assertEquals(3, result.total());
        List<Long> idsAfter = chunkIds(code);
        assertTrue(idsAfter.containsAll(idsBefore),
                "原有两条的 id 必须都还在：before=" + idsBefore + " after=" + idsAfter + " result=" + result);
    }

    /** K2-4. 删掉中间一段：只留 deleted=1，向量行数同步减一。 */
    @Test
    void 删除中间一段时向量同步清理() {
        String code = PREFIX + "sync-remove";
        service.createDoc(new CreateDocCommand(code, "删除", "java", null, null));
        service.syncChunks(code, List.of(
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第1章 > 1.1", "垃圾回收器有 G1 和 ZGC"),
                new ChunkCommand("第2章", "内存泄漏用 jmap 和 MAT 排查")));

        KnowledgeService.ChunkSyncResult result = service.syncChunks(code, List.of(
                new ChunkCommand("第1章", "JVM 内存分为堆和栈"),
                new ChunkCommand("第2章", "内存泄漏用 jmap 和 MAT 排查")));

        assertEquals(1, result.deleted());
        KnowledgeDocView view = viewOf(code);
        assertEquals(2, view.chunkCount());
        assertEquals(2, view.vectorCount(), "删片段必须连向量一起删");
    }

    private List<Long> chunkIds(String docCode) {
        Long docId = docRepository.findByCode(docCode).orElseThrow().getId();
        return chunkRepository.findByDocIdOrderByChunkIndexAsc(docId).stream()
                .map(chunk -> chunk.getId())
                .toList();
    }

    /** K5 的上传入口：文档不存在就建，切分规则用 MarkdownChunker。 */
    @Test
    void 上传markdown时自动建文档并切分() {
        String code = PREFIX + "upload";
        String md = """
                # 索引与事务

                开篇一句引言，说明这篇讲什么。

                ## 索引失效

                隐式类型转换会让索引失效，比如字符串列传了数字。

                ## 事务隔离

                隔离级别有四级，MySQL 默认可重复读。
                """;

        KnowledgeService.ChunkSyncResult result = service.importMarkdown(
                code, "索引与事务", "java", "upload/index.md", md);

        assertEquals(3, result.total(), "引言 + 两个二级小节");
        assertEquals(3, result.inserted());
        KnowledgeDocView view = viewOf(code);
        assertEquals("索引与事务", view.title());
        assertEquals("java", view.category());
        assertEquals(3, view.vectorCount());

        // 再传一次同样的内容：必须整篇复用，一条都不重算
        KnowledgeService.ChunkSyncResult again = service.importMarkdown(
                code, "索引与事务", "java", "upload/index.md", md);
        assertEquals(3, again.reused());
        assertEquals(0, again.inserted());
    }
}
