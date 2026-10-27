package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.InterviewerKnowledge;
import cn.edu.zjut.interview.domain.KnowledgeChunk;
import cn.edu.zjut.interview.domain.KnowledgeDoc;
import cn.edu.zjut.interview.domain.KnowledgeVector;
import cn.edu.zjut.interview.dto.ChunkCommand;
import cn.edu.zjut.interview.dto.CreateDocCommand;
import cn.edu.zjut.interview.dto.KnowledgeDocView;
import cn.edu.zjut.interview.knowledge.EmbeddingProvider;
import cn.edu.zjut.interview.knowledge.KnowledgeIndexVersion;
import cn.edu.zjut.interview.knowledge.MarkdownChunker;
import cn.edu.zjut.interview.repository.InterviewerKnowledgeRepository;
import cn.edu.zjut.interview.repository.KnowledgeChunkRepository;
import cn.edu.zjut.interview.repository.KnowledgeDocRepository;
import cn.edu.zjut.interview.repository.KnowledgeVectorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位知识库的读写（K1/K2）：文档、片段、向量、角色挂载四张表在这里保持一致。
 *
 * <p>写入是<b>按指纹增量同步</b>：{@link #syncChunks} 逐条比对片段指纹，命中的复用（含向量），
 * 只有变化的片段才重算向量，消失的连向量一起删。整篇重传同一份 md 是零写入。
 *
 * <p>向量只是缓存：算不出来不影响片段本身，删掉后 {@link #reindexDoc} 可以从片段重建。
 */
@Service
public class KnowledgeService {

    private final KnowledgeDocRepository docRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeVectorRepository vectorRepository;
    private final InterviewerKnowledgeRepository mountRepository;
    private final EmbeddingProvider embeddingProvider;
    /** 写路径的失效通知：改动内容后加版本，检索侧据此重建快照（见 {@link KnowledgeIndexVersion}）。 */
    private final KnowledgeIndexVersion indexVersion;

    public KnowledgeService(KnowledgeDocRepository docRepository,
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

    /** 建文档；code 已存在抛 409 —— 幂等交给调用方（脚本按 code upsert）。 */
    @Transactional
    public KnowledgeDocView createDoc(CreateDocCommand cmd) {
        if (cmd == null || cmd.code() == null || cmd.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档 code 不能为空");
        }
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档 title 不能为空");
        }
        if (docRepository.existsByCode(cmd.code())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文档 code 已存在：" + cmd.code());
        }
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setCode(cmd.code());
        doc.setTitle(cmd.title());
        doc.setCategory(cmd.category());
        doc.setSourcePath(cmd.sourcePath());
        doc.setDescription(cmd.description());
        doc.setEnabled(Boolean.TRUE);
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        docRepository.save(doc);
        return toView(doc);
    }

    /**
     * 覆盖式写入片段（对外仍叫 replace：脚本每次提交的是整篇文档的完整切分结果）。
     *
     * <p>内部是<b>按内容指纹增量同步</b>而不是「全删重建」：
     * 逐条比对 {@code (heading + "\n" + text)} 的指纹，命中的片段整行复用（连 id 和向量一起留），
     * 只有指纹变了或新增的片段才重新算向量，消失的片段连同向量删掉。
     * 这样「改一个字重跑」只重算那一条，而不是把整篇 20 个片段的向量都重算一遍。
     *
     * <p>不做「靠位置对齐」的 diff：在中间插一段会让后面所有位置整体偏移，
     * 按位置比就会把后面每一条都判成「变了」。指纹与位置无关，插入/删除/换序都不会误伤。
     *
     * @return 实际写入的片段条数
     */
    @Transactional
    public int replaceChunks(String docCode, List<ChunkCommand> chunks) {
        return syncChunks(docCode, chunks).total();
    }

    /** 同 {@link #replaceChunks}，但返回「动了多少」的明细，供导入脚本核对增量是否生效。 */
    @Transactional
    public ChunkSyncResult syncChunks(String docCode, List<ChunkCommand> chunks) {
        KnowledgeDoc doc = requireDoc(docCode);
        Long docId = doc.getId();

        List<KnowledgeChunk> existing = chunkRepository.findByDocIdOrderByChunkIndexAsc(docId);
        Map<Long, KnowledgeVector> vectorByChunkId = new HashMap<>();
        for (KnowledgeVector vector : vectorRepository.findByDocId(docId)) {
            vectorByChunkId.put(vector.getChunkId(), vector);
        }

        List<ChunkCommand> incoming = chunks == null ? List.of() : chunks;
        List<Long> targets = new ArrayList<>(incoming.size());
        for (ChunkCommand cmd : incoming) {
            targets.add(fingerprintOf(cmd));
        }

        // 快路径：条数、顺序、指纹全一致且向量都有效 => 一行不动。
        // 这是「同一份 md 跑两次」的常态，省掉全部写操作。
        // 内容没变就不动检索版本号：否则每次重跑导入脚本都会让检索侧白重建一次索引。
        if (sameFingerprints(existing, targets) && allVectorsValid(existing, vectorByChunkId)) {
            touch(doc);
            return new ChunkSyncResult(existing.size(), existing.size(), 0, 0, 0);
        }

        // 指纹 -> 候选片段队列。同一指纹可能出现多次（两段正文一字不差），用队列逐条领。
        Map<Long, Deque<KnowledgeChunk>> pool = new HashMap<>();
        for (KnowledgeChunk chunk : existing) {
            pool.computeIfAbsent(chunk.getFingerprint(), key -> new ArrayDeque<>()).add(chunk);
        }

        LocalDateTime now = LocalDateTime.now();
        List<Long> keptIds = new ArrayList<>(incoming.size());
        int index = 0;
        int inserted = 0;
        int reused = 0;
        int reembedded = 0;

        for (ChunkCommand cmd : incoming) {
            String heading = headingOf(cmd);
            String text = textOf(cmd);
            Deque<KnowledgeChunk> candidates = pool.get(fingerprintOf(cmd));
            KnowledgeChunk matched = candidates == null ? null : candidates.poll();

            if (matched != null) {
                // 命中：正文一字不差，只可能因为前面插删导致序号漂移，改序号即可
                matched.setChunkIndex(index);
                matched.setHeading(heading);
                matched.setHypeQuestions(hypeOf(cmd));
                chunkRepository.save(matched);
                keptIds.add(matched.getId());
                reused++;

                KnowledgeVector vector = vectorByChunkId.get(matched.getId());
                if (!isVectorValid(vector)) {
                    // 换了 embedding 模型或清了向量缓存：片段留着，向量重算
                    if (vector != null) {
                        vectorRepository.delete(vector);
                        vectorRepository.flush();
                    }
                    writeVector(matched.getId(), docId, embedTextOf(cmd));
                    reembedded++;
                }
            } else {
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocId(docId);
                chunk.setChunkIndex(index);
                chunk.setHeading(heading);
                chunk.setText(text);
                chunk.setCharCount(text.length());
                chunk.setHypeQuestions(hypeOf(cmd));
                chunk.setFingerprint(fingerprintOf(cmd));
                chunk.setCreatedAt(now);
                chunkRepository.save(chunk);
                // IDENTITY 主键在 save 时就已经回填，所以这里能直接拿到 chunkId
                writeVector(chunk.getId(), docId, embedTextOf(cmd));
                inserted++;
            }
            index++;
        }

        // 池子里剩下的就是「原文有、新版本没有」的片段，连同向量一起删
        List<KnowledgeChunk> removed = new ArrayList<>();
        List<KnowledgeVector> removedVectors = new ArrayList<>();
        for (KnowledgeChunk chunk : existing) {
            if (!keptIds.contains(chunk.getId())) {
                removed.add(chunk);
                KnowledgeVector vector = vectorByChunkId.get(chunk.getId());
                if (vector != null) {
                    removedVectors.add(vector);
                }
            }
        }
        if (!removedVectors.isEmpty()) {
            vectorRepository.deleteAll(removedVectors);
            vectorRepository.flush();
        }
        if (!removed.isEmpty()) {
            chunkRepository.deleteAll(removed);
            chunkRepository.flush();
        }

        touch(doc);
        // 片段/向量有过变化 => 通知检索侧快照作废
        indexVersion.bump();
        return new ChunkSyncResult(incoming.size(), reused, inserted, removed.size(), reembedded);
    }

    /** 增量同步的明细：total = reused + inserted。 */
    public record ChunkSyncResult(int total, int reused, int inserted, int deleted, int reembedded) {
    }

    /**
     * 前端上传一篇 md：文档不存在就建、存在就更新元信息，然后切分并同步片段。
     *
     * <p>切分用 {@link MarkdownChunker}，规则与 {@code scripts/import_knowledge.py} 一致 ——
     * 否则「脚本导的」与「页面上传的」同一篇文档会切出不同粒度。
     *
     * <p>为什么让后端切而不是前端切：切分规则要跟着检索/阈值一起演进，
     * 分散到浏览器里就会出现「旧前端 + 新后端」切出过期粒度。
     */
    @Transactional
    public ChunkSyncResult importMarkdown(String docCode, String title, String category,
                                          String sourcePath, String markdown) {
        if (docCode == null || docCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档 code 不能为空");
        }
        KnowledgeDoc doc = docRepository.findByCode(docCode).orElse(null);
        if (doc == null) {
            if (title == null || title.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新建文档时 title 不能为空");
            }
            LocalDateTime now = LocalDateTime.now();
            doc = new KnowledgeDoc();
            doc.setCode(docCode);
            doc.setTitle(title);
            doc.setCategory(category);
            doc.setSourcePath(sourcePath);
            doc.setEnabled(Boolean.TRUE);
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);
            docRepository.save(doc);
        } else {
            // 只更新传了值的字段：前端改标题不该把分类清空
            if (title != null && !title.isBlank()) {
                doc.setTitle(title);
            }
            if (category != null && !category.isBlank()) {
                doc.setCategory(category);
            }
            if (sourcePath != null && !sourcePath.isBlank()) {
                doc.setSourcePath(sourcePath);
            }
        }

        List<ChunkCommand> commands = new ArrayList<>();
        for (MarkdownChunker.Chunk chunk : MarkdownChunker.chunk(markdown)) {
            commands.add(new ChunkCommand(chunk.heading(), chunk.text()));
        }
        return syncChunks(docCode, commands);
    }

    private static String headingOf(ChunkCommand cmd) {
        return cmd == null ? null : cmd.heading();
    }

    private static String textOf(ChunkCommand cmd) {
        return cmd == null || cmd.text() == null ? "" : cmd.text();
    }

    /** 假设问题（K6），空串归一成 null，免得库里存一堆空行。 */
    private static String hypeOf(ChunkCommand cmd) {
        if (cmd == null || cmd.hypeQuestions() == null) {
            return null;
        }
        String trimmed = cmd.hypeQuestions().strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 送去算向量的文本 = 正文 + 假设问题。
     *
     * <p>把假设问题也嵌进去，是为了让「用户按自己的说法问」能命中这段：
     * 正文里写的是「GC Root 引用着，回收不掉」，假设问题里是「内存泄漏怎么排查」，
     * 两者的字面重合完全不同。换 embedding 模型时这行决定了向量口径。
     */
    private static String embedTextOf(ChunkCommand cmd) {
        String text = textOf(cmd);
        String hype = hypeOf(cmd);
        return hype == null ? text : text + "\n" + hype;
    }

    /**
     * 片段指纹：标题 + 正文 + 假设问题。
     *
     * <p>三者任一变了都要重算向量：标题与假设问题都进索引，正文进提示词。
     * 假设问题跟着正文一起变，保证「重新生成 HyPE」不会留下旧问题与新正文并存的错配。
     */
    private static long fingerprintOf(ChunkCommand cmd) {
        String heading = headingOf(cmd);
        String hype = hypeOf(cmd);
        return fnv1a64((heading == null ? "" : heading) + "\n" + textOf(cmd)
                + "\n" + (hype == null ? "" : hype));
    }

    private static boolean sameFingerprints(List<KnowledgeChunk> existing, List<Long> targets) {
        if (existing.size() != targets.size()) {
            return false;
        }
        for (int i = 0; i < targets.size(); i++) {
            if (!targets.get(i).equals(existing.get(i).getFingerprint())) {
                return false;
            }
        }
        return true;
    }

    private boolean allVectorsValid(List<KnowledgeChunk> chunks, Map<Long, KnowledgeVector> vectorByChunkId) {
        for (KnowledgeChunk chunk : chunks) {
            if (!isVectorValid(vectorByChunkId.get(chunk.getId()))) {
                return false;
            }
        }
        return true;
    }

    /** 向量的有效性 = 「模型 + 维度」都等于当前 provider，跨模型的向量不可比。 */
    private boolean isVectorValid(KnowledgeVector vector) {
        return vector != null
                && vector.getDim() != null && vector.getDim() == embeddingProvider.dimension()
                && embeddingProvider.modelName().equals(vector.getModel());
    }

    /**
     * 删除文档：级联删 chunk / vector / 角色挂载。code 不存在抛 404。
     *
     * <p>三张表都没有外键约束，级联在这里手工做：漏一张就会留下指向不存在文档的孤儿行，
     * 检索时按 doc_id 反查会拿到空结果。
     */
    @Transactional
    public void deleteDoc(String docCode) {
        KnowledgeDoc doc = requireDoc(docCode);
        Long docId = doc.getId();
        vectorRepository.flush();
        vectorRepository.deleteByDocId(docId);
        chunkRepository.deleteByDocId(docId);
        mountRepository.deleteByDocId(docId);
        docRepository.delete(doc);
        // 文档没了，它的片段必须从检索快照里消失
        indexVersion.bump();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocView> listDocs() {
        List<KnowledgeDoc> docs = docRepository.findAllByOrderByIdAsc();
        List<KnowledgeDocView> views = new ArrayList<>(docs.size());
        for (KnowledgeDoc doc : docs) {
            views.add(toView(doc));
        }
        return views;
    }

    /** 单篇文档的视图，供 GET /docs/{code} 使用；code 不存在抛 404。 */
    @Transactional(readOnly = true)
    public KnowledgeDocView getDoc(String docCode) {
        return toView(requireDoc(docCode));
    }

    /** 某角色勾选的文档 code 列表。 */
    @Transactional(readOnly = true)
    public List<String> docCodesOf(String interviewerCode) {
        if (interviewerCode == null || interviewerCode.isBlank()) {
            return List.of();
        }
        List<InterviewerKnowledge> mounts = mountRepository.findByInterviewerCode(interviewerCode);
        if (mounts.isEmpty()) {
            return List.of();
        }
        List<Long> docIds = new ArrayList<>(mounts.size());
        for (InterviewerKnowledge mount : mounts) {
            docIds.add(mount.getDocId());
        }
        Map<Long, String> codeById = new LinkedHashMap<>();
        for (KnowledgeDoc doc : docRepository.findAllById(docIds)) {
            codeById.put(doc.getId(), doc.getCode());
        }
        List<String> codes = new ArrayList<>(docIds.size());
        for (Long docId : docIds) {
            String code = codeById.get(docId);
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 整份覆盖某角色的勾选（先删后插）。docCodes 里出现未知 code => 400。 */
    @Transactional
    public void setDocCodesOf(String interviewerCode, List<String> docCodes) {
        if (interviewerCode == null || interviewerCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色 code 不能为空");
        }
        // LinkedHashMap 顺带去重：重复 code 会撞 uk_interviewer_knowledge 唯一约束
        Map<String, Long> idByCode = new LinkedHashMap<>();
        if (docCodes != null) {
            for (String code : docCodes) {
                if (code == null || code.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "docCodes 里不能有空值");
                }
                KnowledgeDoc doc = docRepository.findByCode(code)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "未知文档 code：" + code));
                idByCode.put(doc.getCode(), doc.getId());
            }
        }

        mountRepository.flush();
        mountRepository.deleteByInterviewerCode(interviewerCode);
        mountRepository.flush();
        for (Map.Entry<String, Long> entry : idByCode.entrySet()) {
            InterviewerKnowledge mount = new InterviewerKnowledge();
            mount.setInterviewerCode(interviewerCode);
            mount.setDocId(entry.getValue());
            mountRepository.save(mount);
        }
    }

    /** 运维用：清了向量后重建（K2 脚本会调）。返回重建条数。 */
    @Transactional
    public int reindexDoc(String docCode) {
        KnowledgeDoc doc = requireDoc(docCode);
        Long docId = doc.getId();
        List<KnowledgeChunk> chunks = chunkRepository.findByDocIdOrderByChunkIndexAsc(docId);

        vectorRepository.flush();
        vectorRepository.deleteByDocId(docId);
        // 立刻刷出 DELETE：重建沿用同一个 chunk_id 主键，旧行没删干净会变成 update 而不是 insert
        vectorRepository.flush();

        for (KnowledgeChunk chunk : chunks) {
            writeVector(chunk.getId(), docId, chunk.getText());
        }
        touch(doc);
        // 向量整份换过 => 向量路的候选全变了，快照必须重建
        indexVersion.bump();
        return chunks.size();
    }

    /** 写一条向量：float[] 序列化成小端字节序的 BLOB。 */
    private void writeVector(Long chunkId, Long docId, String text) {
        float[] vector = embeddingProvider.embed(text);
        KnowledgeVector row = new KnowledgeVector();
        row.setChunkId(chunkId);
        row.setDocId(docId);
        row.setModel(embeddingProvider.modelName());
        row.setDim(vector.length);
        row.setEmbedding(toBytes(vector));
        row.setUpdatedAt(LocalDateTime.now());
        vectorRepository.save(row);
    }

    /** 用 ByteBuffer 而不是手拼位移：字节序由缓冲区的默认（大端）统一，读侧照此解析。 */
    private static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** 文档元信息变过就更新 updated_at，前端据此判断「这份知识何时导入过」。 */
    private static void touch(KnowledgeDoc doc) {
        doc.setUpdatedAt(LocalDateTime.now());
    }

    private KnowledgeDoc requireDoc(String docCode) {
        if (docCode == null || docCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档 code 不能为空");
        }
        return docRepository.findByCode(docCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到文档：" + docCode));
    }

    private KnowledgeDocView toView(KnowledgeDoc doc) {
        Long docId = doc.getId();
        long chunkCount = chunkRepository.countByDocId(docId);
        List<KnowledgeVector> vectors = vectorRepository.findByDocId(docId);
        long vectorCount = vectors.size();
        String model = vectors.isEmpty() ? null : vectors.get(0).getModel();
        Integer dim = vectors.isEmpty() ? null : vectors.get(0).getDim();
        return KnowledgeDocView.of(doc, chunkCount, vectorCount, model, dim == null ? 0 : dim);
    }

    /**
     * FNV-1a 64 位哈希，用作片段内容指纹。
     *
     * <p>不直接用 {@code String.hashCode()}：它只有 32 位，长文本碰撞概率偏高，
     * 而指纹碰撞的后果是「正文改了却没重算向量」。
     */
    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
