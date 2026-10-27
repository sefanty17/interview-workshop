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
}
