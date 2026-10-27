package cn.edu.zjut.interview.knowledge;

import cn.edu.zjut.interview.domain.InterviewerKnowledge;
import cn.edu.zjut.interview.domain.KnowledgeChunk;
import cn.edu.zjut.interview.domain.KnowledgeDoc;
import cn.edu.zjut.interview.domain.KnowledgeVector;
import cn.edu.zjut.interview.dto.KnowledgeHit;
import cn.edu.zjut.interview.repository.InterviewerKnowledgeRepository;
import cn.edu.zjut.interview.repository.KnowledgeChunkRepository;
import cn.edu.zjut.interview.repository.KnowledgeDocRepository;
import cn.edu.zjut.interview.repository.KnowledgeVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：BM25 + 向量两路召回 → RRF 融合 → 逐条阈值过滤 → topK。
 *
 * <p>为什么两路都要：纯向量（本项目的 hashing 实现本质是字面哈希）对专有名词的形状不敏感，
 * 纯关键词对「换个说法」就废；两者重合但不冗余，接口留了 {@link EmbeddingProvider} 随时能换真模型。
 *
 * <p>为什么用 RRF 而不是加权求和：余弦落在 [0,1]，BM25 无上界，两者不可加；
 * 只比排名就不必在换模型时重调权重。
 *
 * <p>索引生命周期：内容由 {@link KnowledgeIndexVersion} 的版本号驱动失效，不做定时刷新，
 * 因为知识库随时可能被导入脚本改写。{@code volatile} 快照 + 双检锁保证只重建一次。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** RRF 的平滑常数，取业界常用的 60：让第一名与第二十名的差距不至于过于悬殊。 */
    private static final int RRF_K = 60;

    private static final int DEFAULT_TOP_K = 5;

    /**
     * 阈值依据 {@code scripts/eval_knowledge.py --calibrate} 的 26 正 / 6 负实测分布：
     * <ul>
     *   <li>BM25：正样本最低 2.47、负样本最高 2.69 —— 3.0 能拦掉全部负样本，
     *       代价是「堆和栈分别放什么东西」「@Transactional 什么时候失效」这两条弱字面重合的问题查不到。
     *       门槛是<b>注入</b>的门槛，宁漏不错：把无关内容塞进 prompt 比不塞更伤回答。</li>
     *   <li>向量：正样本最低 0.132、负样本最高 0.184，<b>两堆重叠，hashing 实现下向量分不能当门槛</b>。
     *       0.30 只在「查询与片段字面高度重合」时才通过（实测 0.3~0.6），
     *       等于把实际筛选权交给 BM25；换真语义模型后这个值必须重新标定。</li>
     * </ul>
     */
    private static final double DEFAULT_MIN_VECTOR_SCORE = 0.30;
    private static final double DEFAULT_MIN_BM25_SCORE = 3.0;

    /**
     * 两路权重（加权 RRF 的投票权）。权重扫描结果（26 条正样本，Hit@1 / Hit@3 / MRR）：
     * 0.0 → 96.2% / 96.2% / 0.969；0.2 → 96.2% / 100% / 0.974；0.4 → 92.3% / 100% / 0.949；
     * 1.0（等权）→ 92.3% / 96.2% / 0.950。等权反而最差，因为 hashing 向量路的排序质量只有
     * 73.1% Hit@1，等权等于让它拿到一半投票权、把 BM25 排对的位置挤下去。
     */
    private static final double DEFAULT_VECTOR_WEIGHT = 0.2;
    private static final double DEFAULT_BM25_WEIGHT = 1.0;

    /** 接口层允许的最大 topK，超过一律截断（防止前端一次拉空整库）。 */
    public static final int MAX_TOP_K = 20;

    /** BM25 的两个经验参数：k1 控制词频饱和，b 控制长度惩罚。 */
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;

    /** 单路候选池下限：topK 很小时也要留够候选给 RRF，否则融合没有意义。 */
    private static final int MIN_CANDIDATE_POOL = 20;

    private final KnowledgeDocRepository docRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeVectorRepository vectorRepository;
    private final InterviewerKnowledgeRepository mountRepository;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeIndexVersion indexVersion;

    /** 当前快照；null 表示还没建过。 */
    private volatile RetrievalIndex cache;
    /** 快照对应的版本号。 */
    private volatile long cachedVersion;

    public RetrievalService(KnowledgeDocRepository docRepository,
                            KnowledgeChunkRepository chunkRepository,
                            KnowledgeVectorRepository vectorRepository,
                            InterviewerKnowledgeRepository mountRepository,
                            EmbeddingProvider embeddingProvider,
                            KnowledgeIndexVersion indexVersion) {
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.vectorRepository = vectorRepository;
        this.mountRepository = mountRepository;
        this.embeddingProvider = embeddingProvider;
        this.indexVersion = indexVersion;
    }

    /**
     * 检索参数：topK + 两路各自的阈值 + RRF 的两路权重。
     *
     * <p>权重不是拍脑袋定的：用 {@code data/eval/knowledge-questions.json} 那 26 条问题实测，
     * 等权（1:1）时混合检索的 Hit@1 反而低于单 BM25（92.3% vs 96.2%）——
     * {@link HashingEmbeddingProvider} 对「短查询 vs 长片段」的余弦天然偏低（正确片段也只有 0.2 左右），
     * 等权融合等于让噪声更大的那一路拿到一半投票权。降到 0.2 后 Hit@3 反而比单路更高。
     * 换真语义模型后这个权重应重新标定（方法见 scripts/eval_knowledge.py --sweep）。
     */
    public record SearchOptions(int topK, double minVectorScore, double minBm25Score,
                                double vectorWeight, double bm25Weight) {

        public static SearchOptions defaults() {
            return new SearchOptions(DEFAULT_TOP_K, DEFAULT_MIN_VECTOR_SCORE, DEFAULT_MIN_BM25_SCORE,
                    DEFAULT_VECTOR_WEIGHT, DEFAULT_BM25_WEIGHT);
        }

        /** 只改 topK（其余保持默认），接口层用；topK 非法时回落默认值，超过上限则截断。 */
        public SearchOptions withTopK(int topK) {
            int normalized = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
            return new SearchOptions(normalized, minVectorScore, minBm25Score, vectorWeight, bm25Weight);
        }

        /** 覆盖阈值：评测脚本与前端「试搜」要放开门槛看全部候选。 */
        public SearchOptions withThresholds(double minVector, double minBm25) {
            return new SearchOptions(topK, minVector, minBm25, vectorWeight, bm25Weight);
        }

        /** 覆盖两路权重：评测脚本做权重扫描用。 */
        public SearchOptions withWeights(double vector, double bm25) {
            return new SearchOptions(topK, minVectorScore, minBm25Score,
                    vector <= 0 ? 0.0 : vector, bm25 <= 0 ? 0.0 : bm25);
        }
    }

    /** 检索模式；字符串入口见 {@link #parse}，不认识的串一律当 {@link #HYBRID}。 */
    public enum RetrievalMode {
        HYBRID("hybrid"),
        BM25_ONLY("bm25"),
        VECTOR_ONLY("vector");

        private final String wireName;

        RetrievalMode(String wireName) {
            this.wireName = wireName;
        }

        /** 对外输出的模式名，接口回显用。 */
        public String wireName() {
            return wireName;
        }

        /** 字符串解析：{@code hybrid}/{@code bm25}/{@code vector}，其它值按混合检索处理。 */
        public static RetrievalMode parse(String mode) {
            if (mode == null || mode.isBlank()) {
                return HYBRID;
            }
            return switch (mode.trim().toLowerCase(Locale.ROOT)) {
                case "bm25", "bm25_only" -> BM25_ONLY;
                case "vector", "vector_only" -> VECTOR_ONLY;
                default -> HYBRID;
            };
        }
    }

    /**
     * 检索入口。返回已按相关度降序、且已过阈值的命中列表，可以是空列表。
     *
     * @param query           查询串；空白直接返回空列表（400 由 Controller 负责）
     * @param interviewerCode 角色卡 code，非空且挂载了文档时优先按挂载范围检索
     * @param jobCode         岗位 code，没挂载时按文档 category 过滤
     * @param options         检索参数，null 用默认值
     * @param mode            检索模式，null 当混合
     */
    public List<KnowledgeHit> search(String query, String interviewerCode, String jobCode,
                                     SearchOptions options, RetrievalMode mode) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        SearchOptions opts = options == null ? SearchOptions.defaults() : options;
        RetrievalMode effective = mode == null ? RetrievalMode.HYBRID : mode;
        // 服务层自己也兜一次底：直接调 service 的地方（下一批 K4）不该因为传了 0 或 999 就把整库拉出来
        int topK = opts.topK() <= 0 ? DEFAULT_TOP_K : Math.min(opts.topK(), MAX_TOP_K);

        RetrievalIndex index = currentIndex();
        List<Integer> scope = scopePositions(index, interviewerCode, jobCode);
        if (scope.isEmpty()) {
            return List.of();
        }

        int pool = Math.max(topK * 4, MIN_CANDIDATE_POOL);
        Map<Integer, Double> bm25Scores = effective == RetrievalMode.VECTOR_ONLY
                ? Map.of() : bm25Scores(index, scope, query);
        Map<Integer, Double> vectorScores = effective == RetrievalMode.BM25_ONLY
                ? Map.of() : vectorScores(index, scope, query);

        List<Integer> bm25Ranked = topPositions(bm25Scores, pool);
        List<Integer> vectorRanked = topPositions(vectorScores, pool);

        Map<Integer, Candidate> fused = new LinkedHashMap<>();
        for (int rank = 0; rank < bm25Ranked.size(); rank++) {
            int position = bm25Ranked.get(rank);
            Candidate candidate = fused.computeIfAbsent(position, key -> new Candidate(key));
            // 加权 RRF：w/(k+rank)，两路权重不同则不平等投票
            candidate.rrf += opts.bm25Weight() / (RRF_K + rank + 1);
            candidate.bm25Score = bm25Scores.get(position);
        }
        for (int rank = 0; rank < vectorRanked.size(); rank++) {
            int position = vectorRanked.get(rank);
            Candidate candidate = fused.computeIfAbsent(position, key -> new Candidate(key));
            candidate.rrf += opts.vectorWeight() / (RRF_K + rank + 1);
            candidate.vectorScore = vectorScores.get(position);
        }

        // 阈值是逐条判定：融合后排名靠后的条目可能只被某一路以极低分召回，注进提示词就是噪声。
        // 「问今天天气」这类不相关问题靠这里整条丢掉，最终返回空列表。
        List<Candidate> passed = new ArrayList<>(fused.size());
        for (Candidate candidate : fused.values()) {
            if (candidate.vectorScore >= opts.minVectorScore()
                    || candidate.bm25Score >= opts.minBm25Score()) {
                passed.add(candidate);
            }
        }
        passed.sort(RESULT_ORDER);

        List<RetrievalIndex.Doc> docs = index.docs();
        List<KnowledgeHit> hits = new ArrayList<>(Math.min(passed.size(), topK));
        for (int i = 0; i < passed.size() && hits.size() < topK; i++) {
            Candidate candidate = passed.get(i);
            RetrievalIndex.Doc doc = docs.get(candidate.position);
            hits.add(new KnowledgeHit(doc.chunkId(), doc.docId(), doc.docCode(), doc.docTitle(),
                    doc.heading(), doc.text(), candidate.rrf, candidate.vectorScore, candidate.bm25Score));
        }
        return hits;
    }

    /** 范围内片段数：前端「试搜」据此判断是不是根本没挂语料。 */
    public int scopeSize(String interviewerCode, String jobCode) {
        return scopePositions(currentIndex(), interviewerCode, jobCode).size();
    }

    /** 融合分的排序：降序，同分按单路最高原始分（向量优先）再按 chunk_id 升序，保证结果稳定。 */
    private static final Comparator<Candidate> RESULT_ORDER =
            Comparator.comparingDouble((Candidate candidate) -> candidate.rrf).reversed()
                    .thenComparing(Comparator.comparingDouble(
                            (Candidate candidate) -> Math.max(candidate.vectorScore, candidate.bm25Score)).reversed())
                    .thenComparing(Comparator.comparingDouble(
                            (Candidate candidate) -> candidate.vectorScore).reversed())
                    // 索引本身按 chunk_id 升序构建，所以下标升序就等价于 chunk_id 升序
                    .thenComparingInt(candidate -> candidate.position);

    /** 融合过程中的可变累加器，只在一次检索内存在。 */
    private static final class Candidate {
        private final int position;
        private double rrf;
        private double vectorScore;
        private double bm25Score;

        private Candidate(int position) {
            this.position = position;
        }
    }

    // ------------------------------------------------------------------ 索引与范围

    /** 取当前快照：版本号一致就直接用，否则（双检锁）重建一次。 */
    private RetrievalIndex currentIndex() {
        long version = indexVersion.current();
        RetrievalIndex snapshot = cache;
        if (snapshot != null && cachedVersion == version) {
            return snapshot;
        }
        synchronized (this) {
            long latest = indexVersion.current();
            if (cache == null || cachedVersion != latest) {
                cache = build(latest);
                cachedVersion = latest;
            }
            return cache;
        }
    }

    /**
     * 重建索引：一次把片段、BM25 统计量、向量矩阵全算好，之后只读。
     *
     * <p>只收 {@code enabled = TRUE} 的文档；向量只收与当前 provider「模型 + 维度」都一致的，
     * 跨模型的向量不可比，宁缺勿滥（该位置留 null，向量路自然跳过它）。
     */
    private RetrievalIndex build(long version) {
        Map<Long, KnowledgeDoc> enabledDocs = new HashMap<>();
        for (KnowledgeDoc doc : docRepository.findAll()) {
            if (Boolean.TRUE.equals(doc.getEnabled())) {
                enabledDocs.put(doc.getId(), doc);
            }
        }

        List<KnowledgeChunk> chunks = chunkRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        List<RetrievalIndex.Doc> docs = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeDoc doc = enabledDocs.get(chunk.getDocId());
            if (doc == null) {
                // 文档被停用或已删：片段与向量都还在库里，但不参与检索
                continue;
            }
            docs.add(new RetrievalIndex.Doc(chunk.getId(), doc.getId(), doc.getCode(), doc.getTitle(),
                    chunk.getHeading(), chunk.getText(), chunk.getHypeQuestions()));
        }

        int dim = embeddingProvider.dimension();
        Map<Long, float[]> vectorByChunkId = new HashMap<>();
        for (KnowledgeVector row : vectorRepository.findByModelAndDim(embeddingProvider.modelName(), dim)) {
            float[] vector = decode(row, dim);
            if (vector != null) {
                vectorByChunkId.put(row.getChunkId(), vector);
            }
        }
        float[][] vectors = new float[docs.size()][];
        for (int i = 0; i < docs.size(); i++) {
            vectors[i] = vectorByChunkId.get(docs.get(i).chunkId());
        }

        log.debug("[Knowledge] 重建检索索引 version={} 片段={} 有向量={}", version, docs.size(), vectorByChunkId.size());
        return new RetrievalIndex(docs, vectors, dim);
    }

    /**
     * BLOB -> float[]。写入侧用 {@code ByteBuffer.putFloat}（默认大端），读侧必须按大端反序列化；
     * 维度以行里的 {@code dim} 为准校验，不按字节数硬除，避免脏数据算出半个向量。
     */
    private static float[] decode(KnowledgeVector row, int dim) {
        Integer rowDim = row.getDim();
        byte[] bytes = row.getEmbedding();
        if (rowDim == null || rowDim.intValue() != dim || bytes == null || bytes.length != dim * 4) {
            return null;
        }
        FloatBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer();
        float[] vector = new float[dim];
        buffer.get(vector);
        return vector;
    }

    /**
     * 检索范围（先定范围再打分）：
     * 角色挂载优先 → 岗位分类次之 → 都没有就是全部启用文档。
     *
     * <p>挂载非空但指向的文档全被停用时返回空集合（不再退化成「全部」），
     * 否则「考官没配语料」会静默变成「用整库语料」，这是更难发现的错。
     */
    private Set<Long> scopeDocIds(String interviewerCode, String jobCode) {
        if (interviewerCode != null && !interviewerCode.isBlank()) {
            List<InterviewerKnowledge> mounts = mountRepository.findByInterviewerCode(interviewerCode);
            if (!mounts.isEmpty()) {
                List<Long> mountedIds = new ArrayList<>(mounts.size());
                for (InterviewerKnowledge mount : mounts) {
                    mountedIds.add(mount.getDocId());
                }
                Set<Long> enabled = new HashSet<>();
                for (KnowledgeDoc doc : docRepository.findAllById(mountedIds)) {
                    if (Boolean.TRUE.equals(doc.getEnabled())) {
                        enabled.add(doc.getId());
                    }
                }
                return enabled;
            }
        }
        boolean byJob = jobCode != null && !jobCode.isBlank();
        Set<Long> ids = new HashSet<>();
        for (KnowledgeDoc doc : docRepository.findAll()) {
            if (!Boolean.TRUE.equals(doc.getEnabled())) {
                continue;
            }
            if (byJob && !jobCode.equals(doc.getCategory())) {
                continue;
            }
            ids.add(doc.getId());
        }
        return ids;
    }

    /** 把范围映射成索引下标（不重建索引，只做过滤）。下标顺序天然是 chunk_id 升序。 */
    private List<Integer> scopePositions(RetrievalIndex index, String interviewerCode, String jobCode) {
        Set<Long> docIds = scopeDocIds(interviewerCode, jobCode);
        if (docIds.isEmpty() || index.size() == 0) {
            return List.of();
        }
        List<RetrievalIndex.Doc> docs = index.docs();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            if (docIds.contains(docs.get(i).docId())) {
                positions.add(i);
            }
        }
        return positions;
    }

    // ------------------------------------------------------------------ 两路召回

    /**
     * BM25（k1=1.2, b=0.75）。
     *
     * <p><b>idf 的 N 与 df 必须同口径</b>：都取「检索范围内」。用全库 df 配范围内 N 会在
     * 范围外更常见时算出负 idf（{@code N - n + 0.5} 变负），相关片段反而被扣分——
     * 只搜 frontend 却问 java 术语时就会踩到。所以先扫一遍范围统计 df，再算分。
     *
     * <p>长度为 0 分的片段不进候选：分数为 0 说明一个查询词都没命中，
     * 把它塞进候选池只会占位，最后一样会被阈值丢掉。
     */
    /** 包级可见：{@code Bm25ScoreTest} 直接对手工构造的索引断言分数符号，不必起 Spring 上下文。 */
    static Map<Integer, Double> bm25Scores(RetrievalIndex index, List<Integer> scope, String query) {
        List<String> queryTerms = dedupe(ChineseTokenizer.tokenize(query));
        Map<Integer, Double> scores = new HashMap<>();
        if (queryTerms.isEmpty()) {
            return scores;
        }

        // 第一遍：范围内每个查询词的 df
        Map<String, Integer> dfInScope = new HashMap<>();
        for (String term : queryTerms) {
            dfInScope.put(term, 0);
        }
        for (int position : scope) {
            for (String term : queryTerms) {
                if (index.termFreq(position, term) > 0) {
                    dfInScope.merge(term, 1, Integer::sum);
                }
            }
        }

        // 第二遍：打分
        int scopeSize = scope.size();
        for (int position : scope) {
            int length = index.termsOf(position).size();
            double score = 0.0;
            for (String term : queryTerms) {
                int freq = index.termFreq(position, term);
                if (freq == 0) {
                    continue;
                }
                int docFreq = dfInScope.get(term);
                double idf = Math.log(1.0 + (scopeSize - docFreq + 0.5) / (docFreq + 0.5));
                score += idf * (freq * (BM25_K1 + 1))
                        / (freq + BM25_K1 * (1 - BM25_B + BM25_B * length / index.avgLength()));
            }
            if (score > 0.0) {
                scores.put(position, score);
            }
        }
        return scores;
    }

    /** 向量路：查询向量与每条片段向量算余弦；没有向量的片段直接跳过。 */
    private Map<Integer, Double> vectorScores(RetrievalIndex index, List<Integer> scope, String query) {
        Map<Integer, Double> scores = new HashMap<>();
        float[] queryVector = embeddingProvider.embed(query);
        if (queryVector == null || queryVector.length != index.dim()) {
            // 维度对不上说明 provider 与索引不同代（理论上版本号会先失效），这一路直接放弃
            log.warn("[Knowledge] 查询向量维度 {} 与索引 {} 不一致，向量路跳过",
                    queryVector == null ? -1 : queryVector.length, index.dim());
            return scores;
        }
        for (int position : scope) {
            float[] vector = index.vectorAt(position);
            if (vector == null) {
                continue;
            }
            scores.put(position, cosine(queryVector, vector));
        }
        return scores;
    }

    /**
     * 余弦相似度 = 点积 / (|a|·|b|)。
     *
     * <p>自己算模长，不假设向量已 L2 归一化：{@link HashingEmbeddingProvider} 归一化过，
     * 但换真模型或脏数据时不能赌这一点。模长为 0 的退化为 0 分，避免 NaN 污染排序。
     */
    private static double cosine(float[] a, float[] b) {
        int common = Math.min(a.length, b.length);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < common; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        for (int i = common; i < a.length; i++) {
            normA += (double) a[i] * a[i];
        }
        for (int i = common; i < b.length; i++) {
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 单路排名：分数降序；同分按下标（即 chunk_id）升序，保证候选池内容是确定的。 */
    private static List<Integer> topPositions(Map<Integer, Double> scores, int limit) {
        List<Integer> positions = new ArrayList<>(scores.keySet());
        positions.sort(Comparator.comparingDouble((Integer position) -> scores.get(position)).reversed()
                .thenComparingInt(position -> position));
        if (positions.size() <= limit) {
            return positions;
        }
        return new ArrayList<>(positions.subList(0, limit));
    }

    /** 查询词去重：BM25 里同一个查询词重复出现只是把分数乘几倍，没有检索意义。 */
    private static List<String> dedupe(List<String> terms) {
        return new ArrayList<>(new LinkedHashSet<>(terms));
    }
}
