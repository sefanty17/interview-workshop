package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.InterviewerKnowledge;
import cn.edu.zjut.interview.domain.KnowledgeChunk;
import cn.edu.zjut.interview.domain.KnowledgeDoc;
import cn.edu.zjut.interview.domain.KnowledgeVector;
import cn.edu.zjut.interview.dto.ChunkCommand;
import cn.edu.zjut.interview.dto.CreateDocCommand;
import cn.edu.zjut.interview.dto.KnowledgeDocView;
import cn.edu.zjut.interview.knowledge.EmbeddingProvider;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位知识库的读写（K1）：文档、片段、向量、角色挂载四张表在这里保持一致。
 *
 * <p>核心约定是<b>覆盖式写入</b>：导入脚本每次把整份 md 重新切一遍，
 * 所以 {@link #replaceChunks} 先清空该文档的片段与向量再写回，
 * 不做增量 diff——增量需要稳定的片段标识，而切分参数一变标识就漂移。
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

    public KnowledgeService(KnowledgeDocRepository docRepository,
                            KnowledgeChunkRepository chunkRepository,
                            KnowledgeVectorRepository vectorRepository,
                            InterviewerKnowledgeRepository mountRepository,
                            EmbeddingProvider embeddingProvider) {
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.vectorRepository = vectorRepository;
        this.mountRepository = mountRepository;
        this.embeddingProvider = embeddingProvider;
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
     * 覆盖式写入片段：先删该 doc 的全部 chunk 与 vector，再插入新 chunk 并立即算向量。
     *
     * <p>{@code chunks} 为空 => 只清空，不报错（等价于「这篇文档暂时没有可用内容」）。
     *
     * @return 实际写入的片段条数
     */
    @Transactional
    public int replaceChunks(String docCode, List<ChunkCommand> chunks) {
        KnowledgeDoc doc = requireDoc(docCode);
        Long docId = doc.getId();

        // 先 flush 再删：同一事务里「删旧 + 插新」时，直接下批量 DELETE 会撞上尚未刷出的旧行
        vectorRepository.flush();
        vectorRepository.deleteByDocId(docId);
        vectorRepository.flush();
        chunkRepository.flush();
        chunkRepository.deleteByDocId(docId);
        chunkRepository.flush();

        if (chunks == null || chunks.isEmpty()) {
            touch(doc);
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int index = 0;
        for (ChunkCommand cmd : chunks) {
            String heading = cmd == null ? null : cmd.heading();
            String text = cmd == null || cmd.text() == null ? "" : cmd.text();
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocId(docId);
            chunk.setChunkIndex(index);
            chunk.setHeading(heading);
            chunk.setText(text);
            chunk.setCharCount(text.length());
            chunk.setFingerprint(fnv1a64((heading == null ? "" : heading) + "\n" + text));
            chunk.setCreatedAt(now);
            chunkRepository.save(chunk);
            // IDENTITY 主键在 save 时就已经回填，所以这里能直接拿到 chunkId
            writeVector(chunk.getId(), docId, text);
            index++;
        }
        touch(doc);
        return index;
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
