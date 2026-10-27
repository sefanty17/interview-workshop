package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.ChunkCommand;
import cn.edu.zjut.interview.dto.CreateDocCommand;
import cn.edu.zjut.interview.dto.KnowledgeDocView;
import cn.edu.zjut.interview.dto.KnowledgeHit;
import cn.edu.zjut.interview.knowledge.EmbeddingProvider;
import cn.edu.zjut.interview.knowledge.HashingEmbeddingProvider;
import cn.edu.zjut.interview.knowledge.RetrievalService;
import cn.edu.zjut.interview.repository.KnowledgeChunkRepository;
import cn.edu.zjut.interview.repository.KnowledgeDocRepository;
import cn.edu.zjut.interview.repository.KnowledgeVectorRepository;
import cn.edu.zjut.interview.service.KnowledgeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位知识库接口（K1 写入 + K3 检索）：文档 CRUD、片段覆盖式导入、向量重建、角色挂载、试搜。
 *
 * <p>路径统一挂 {@code /api}（与其它 Controller 一致），这里不再另加一层
 * {@code /knowledge} 前缀，是因为其中两个接口属于角色卡（{@code /api/interviewers/{code}/knowledge}）。
 *
 * <p>错误语义交给 {@link KnowledgeService} 抛 {@code ResponseStatusException}：
 * code 重复 409、文档不存在 404、未知 docCode 400。检索的 {@code q} 为空同样是 400。
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final KnowledgeService service;
    private final RetrievalService retrievalService;
    private final KnowledgeDocRepository docRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeVectorRepository vectorRepository;
    private final EmbeddingProvider embeddingProvider;

    public KnowledgeController(KnowledgeService service,
                               RetrievalService retrievalService,
                               KnowledgeDocRepository docRepository,
                               KnowledgeChunkRepository chunkRepository,
                               KnowledgeVectorRepository vectorRepository,
                               EmbeddingProvider embeddingProvider) {
        this.service = service;
        this.retrievalService = retrievalService;
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
                chunks.add(new ChunkCommand(item.heading(), item.text(), item.hype()));
            }
        }
        // 返回增量明细而不是只给总数：导入脚本靠它证明「改一个字只重算一条」。
        KnowledgeService.ChunkSyncResult result = service.syncChunks(code, chunks);
        return new SyncView(result.total(), result.reused(), result.inserted(),
                result.deleted(), result.reembedded());
    }

    /**
     * 上传一篇 markdown：后端切分后按指纹同步片段（前端「知识库」页的上传入口用它）。
     *
     * <p>与 {@code PUT /chunks} 的区别：那个要求调用方自己切好，这个收原文。
     * 网页端拿不到 Python 脚本，所以切分必须由后端做。
     */
    @PostMapping("/knowledge/docs/{code}/markdown")
    public SyncView uploadMarkdown(@PathVariable String code,
                                   @RequestBody(required = false) MarkdownRequest request) {
        if (request == null || request.markdown() == null || request.markdown().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "markdown 内容不能为空");
        }
        KnowledgeService.ChunkSyncResult result = service.importMarkdown(
                code, request.title(), request.category(), request.sourcePath(), request.markdown());
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

    /**
     * 混合检索（试搜）：前端「知识库」页用它验证语料挂对了没有。
     *
     * <p>{@code scopeSize} 单独返回，是为了让前端区分「检索没命中」和「压根没挂语料」——
     * 这两种情况 hits 都是空，但用户的处理动作完全不同。
     *
     * <p>阈值（默认 0.30 / 3.0）在这里不开放调参：标定值来自评测脚本，随手改会让「不相关问题不注入」失效。
     */
    @GetMapping("/knowledge/search")
    public SearchView search(@RequestParam(name = "q", required = false) String q,
                             @RequestParam(name = "interviewerCode", required = false) String interviewerCode,
                             @RequestParam(name = "jobCode", required = false) String jobCode,
                             @RequestParam(name = "topK", required = false) Integer topK,
                             @RequestParam(name = "mode", required = false) String mode,
                             @RequestParam(name = "minVector", required = false) Double minVector,
                             @RequestParam(name = "minBm25", required = false) Double minBm25,
                             @RequestParam(name = "wVector", required = false) Double wVector,
                             @RequestParam(name = "wBm25", required = false) Double wBm25) {
        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询词 q 不能为空");
        }
        // topK 缺省/非法用默认 5，超过上限（20）由 withTopK 截断
        RetrievalService.SearchOptions options =
                RetrievalService.SearchOptions.defaults().withTopK(topK == null ? 0 : topK);
        // 阈值可覆盖：前端「试搜」要放开门槛看全部候选，评测脚本也要看未过滤的原始分
        if (minVector != null || minBm25 != null) {
            options = options.withThresholds(
                    minVector == null ? options.minVectorScore() : minVector,
                    minBm25 == null ? options.minBm25Score() : minBm25);
        }
        // 权重可覆盖：评测脚本做两路权重扫描用（默认值由评测集标定，见 RetrievalService 注释）
        if (wVector != null || wBm25 != null) {
            options = options.withWeights(
                    wVector == null ? options.vectorWeight() : wVector,
                    wBm25 == null ? options.bm25Weight() : wBm25);
        }
        RetrievalService.RetrievalMode resolved = RetrievalService.RetrievalMode.parse(mode);
        List<KnowledgeHit> hits = retrievalService.search(q, interviewerCode, jobCode, options, resolved);
        return new SearchView(q, resolved.wireName(),
                retrievalService.scopeSize(interviewerCode, jobCode), options.topK(), hits);
    }

    /** 建文档请求体：字段与 {@link CreateDocCommand} 一一对应。 */
    public record CreateDocRequest(
            String code,
            String title,
            String category,
            String sourcePath,
            String description) {
    }

    /** 单个片段请求体；{@code hype} 是 K6 的假设问题，一行一个，可省略。 */
    public record ChunkItem(String heading, String text, String hype) {
    }

    /** 片段列表请求体：用对象包一层，将来加切分参数时不用改 body 形状。 */
    public record ChunksRequest(List<ChunkItem> chunks) {
    }

    /** 上传 markdown 的请求体：markdown 是原文，切分由后端做。 */
    public record MarkdownRequest(String title, String category, String sourcePath, String markdown) {
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

    /**
     * 检索返回体：回显查询与模式，附上范围大小与命中列表。
     *
     * <p>{@code mode} 是归一化之后的模式名（{@code hybrid}/{@code bm25}/{@code vector}），
     * 传了不认识的串会看到它变成 {@code hybrid}，便于前端自查。
     */
    public record SearchView(
            String query,
            String mode,
            int scopeSize,
            int topK,
            List<KnowledgeHit> hits) {
    }
}
