package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.InterviewerRelation;
import cn.edu.zjut.interview.repository.InterviewerRelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 角色对各方的出厂关系读写（对齐 {@code player_identity/role_relations.rs}）。
 *
 * <p>一张表装一个角色的全部关系：键 {@code me:<身份id>} / {@code ai:<角色code>}。
 * {@link #save} 是<b>整份覆盖</b>语义（先删后插），与前端编辑器的「保存关系」一致。
 */
@Service
public class InterviewerRelationService {

    private final InterviewerRelationRepository repository;

    public InterviewerRelationService(InterviewerRelationRepository repository) {
        this.repository = repository;
    }

    /** 读某个角色的关系表：{@code targetKey -> relationText}，空键值过滤。 */
    public Map<String, String> load(String interviewerCode) {
        Map<String, String> out = new LinkedHashMap<>();
        if (interviewerCode == null || interviewerCode.isBlank()) {
            return out;
        }
        for (InterviewerRelation row : repository.findByInterviewerCode(interviewerCode)) {
            putSanitized(out, row.getTargetKey(), row.getRelationText());
        }
        return out;
    }

    /**
     * 整份覆盖：先清空该角色的关系，再逐条写回（对齐 {@code role_relations::save}）。
     *
     * <p>先落库（{@code flush}）再删，最后再 {@code flush} 一次：同一事务里先插后删时，
     * 直接下批量 DELETE 会撞上尚未刷出的旧行（唯一约束），刷出后再删就没有顺序问题。
     */
    @Transactional
    public void save(String interviewerCode, Map<String, String> relations) {
        if (interviewerCode == null || interviewerCode.isBlank()) {
            return;
        }
        repository.flush();
        repository.deleteByInterviewerCode(interviewerCode);
        repository.flush();
        if (relations == null || relations.isEmpty()) {
            return;
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        relations.forEach((key, value) -> putSanitized(sanitized, key, value));
        for (Map.Entry<String, String> entry : sanitized.entrySet()) {
            InterviewerRelation row = new InterviewerRelation();
            row.setInterviewerCode(interviewerCode);
            row.setTargetKey(entry.getKey());
            row.setRelationText(entry.getValue());
            row.setUpdatedAt(LocalDateTime.now());
            repository.save(row);
        }
    }

    /**
     * 出厂层 + 运行时层合并（对齐 {@code runtime_relations::merge_runtime}）。
     *
     * <p><b>本项目暂无运行时层</b>（关系变化还没落档），调用方传 {@code null} 即可；
     * 签名保留是为了将来接存档时不改调用点。运行时层非空时覆盖出厂层同键。
     */
    public Map<String, String> loadEffective(String interviewerCode, Map<String, String> runtimeLayer) {
        Map<String, String> merged = new LinkedHashMap<>(load(interviewerCode));
        if (runtimeLayer != null) {
            merged.putAll(runtimeLayer);
        }
        return merged;
    }

    /** 键值任一为空/空白就丢掉（对齐 {@code sanitize}）。 */
    private static void putSanitized(Map<String, String> target, String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }
}
