package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.ChunkCommand;
import cn.edu.zjut.interview.dto.CreateDocCommand;
import cn.edu.zjut.interview.dto.KnowledgeDocView;
import cn.edu.zjut.interview.knowledge.EmbeddingProvider;
import cn.edu.zjut.interview.knowledge.HashingEmbeddingProvider;
import cn.edu.zjut.interview.repository.KnowledgeChunkRepository;
import cn.edu.zjut.interview.repository.KnowledgeDocRepository;
import cn.edu.zjut.interview.repository.KnowledgeVectorRepository;
import cn.edu.zjut.interview.service.KnowledgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位知识库接口（K1）：文档 CRUD、片段覆盖式导入、向量重建、角色挂载。
 *
 * <p>路径统一挂 {@code /api}（与其它 Controller 一致），这里不再另加一层
 * {@code /knowledge} 前缀，是因为其中两个接口属于角色卡（{@code /api/interviewers/{code}/knowledge}）。
 *
 * <p>错误语义交给 {@link KnowledgeService} 抛 {@code ResponseStatusException}：
 * code 重复 409、文档不存在 404、未知 docCode 400。
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final KnowledgeService service;
    private final KnowledgeDocRepository docRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeVectorRepository vectorRepository;
    private final EmbeddingProvider embeddingProvider;

    public KnowledgeController(KnowledgeService service,
                               KnowledgeDocRepository docRepository,
                               KnowledgeChunkRepository chunkRepository,
                               KnowledgeVectorRepository vectorRepository,
                               EmbeddingProvider embeddingProvider) {
        this.service = service;
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.vectorRepository = vectorRepository;
        this.embeddingProvider = embeddingProvider;
    }

    @GetMapping("/knowledge/docs")
    public List<KnowledgeDocView> listDocs() {
        return service.listDocs();
    }

    @PostMapping("/knowledge/docs")
    public KnowledgeDocView createDoc(@RequestBody CreateDocRequest request) {
        return service.createDoc(new CreateDocCommand(
                request.code(), request.title(), request.category(),
                request.sourcePath(), request.description()));
    }

    @GetMapping("/knowledge/docs/{code}")
    public KnowledgeDocView getDoc(@PathVariable String code) {
        return service.getDoc(code);
    }

    @DeleteMapping("/knowledge/docs/{code}")
    public ResponseEntity<Void> deleteDoc(@PathVariable String code) {
        service.deleteDoc(code);
        return ResponseEntity.noContent().build();
    }

    /** 覆盖式写入片段：body 里 {@code chunks} 为空数组等于「清空这篇文档的语料」。 */
    @PutMapping("/knowledge/docs/{code}/chunks")
    public SyncView replaceChunks(@PathVariable String code,
                                  @RequestBody(required = false) ChunksRequest request) {
        List<ChunkCommand> chunks = new ArrayList<>();
        if (request != null && request.chunks() != null) {
            for (ChunkItem item : request.chunks()) {
                chunks.add(new ChunkCommand(item.heading(), item.text()));
            }
        }
        // 返回增量明细而不是只给总数：导入脚本靠它证明「改一个字只重算一条」。
        KnowledgeService.ChunkSyncResult result = service.syncChunks(code, chunks);
        return new SyncView(result.total(), result.reused(), result.inserted(),
                result.deleted(), result.reembedded());
    }

    @PostMapping("/knowledge/docs/{code}/reindex")
    public CountView reindex(@PathVariable String code) {
        return new CountView(service.reindexDoc(code));
    }

    @GetMapping("/interviewers/{code}/knowledge")
    public DocCodesView getKnowledgeOf(@PathVariable String code) {
        return new DocCodesView(service.docCodesOf(code));
    }

    @PutMapping("/interviewers/{code}/knowledge")
    public ResponseEntity<Void> setKnowledgeOf(@PathVariable String code,
                                               @RequestBody(required = false) DocCodesRequest request) {
        service.setDocCodesOf(code, request == null || request.docCodes() == null
                ? List.of() : request.docCodes());
        return ResponseEntity.noContent().build();
    }

    /** 健康检查：确认四张表建起来了、向量能算、当前模型维度是多少。 */
    @GetMapping("/knowledge/health")
    public HealthView health() {
        return new HealthView(
                providerId(),
                embeddingProvider.modelName(),
                embeddingProvider.dimension(),
                embeddingProvider.isAvailable(),
                docRepository.count(),
                chunkRepository.count(),
                vectorRepository.count());
    }

    /** 对外报「当前实际生效的实现」而不是配置值：配了 openai 但缺 key 时会回落成 hashing。 */
    private String providerId() {
        return embeddingProvider instanceof HashingEmbeddingProvider ? "hashing" : "openai";
    }

    /** 建文档请求体：字段与 {@link CreateDocCommand} 一一对应。 */
    public record CreateDocRequest(
            String code,
            String title,
            String category,
            String sourcePath,
            String description) {
    }

    /** 单个片段请求体。 */
    public record ChunkItem(String heading, String text) {
    }

    /** 片段列表请求体：用对象包一层，将来加切分参数时不用改 body 形状。 */
    public record ChunksRequest(List<ChunkItem> chunks) {
    }

    public record DocCodesRequest(List<String> docCodes) {
    }

    public record DocCodesView(List<String> docCodes) {
    }

    /** 批量写操作的统一返回：只有条数。 */
    public record CountView(int count) {
    }

    /**
     * 片段同步的返回：{@code count} 是写入后的总条数，
     * 其余字段说明这次到底动了多少（脚本据此验证增量是否生效）。
     */
    public record SyncView(int count, int reused, int inserted, int deleted, int reembedded) {
    }

    public record HealthView(
            String provider,
            String model,
            int dimension,
            boolean available,
            long docCount,
            long chunkCount,
            long vectorCount) {
    }
}
