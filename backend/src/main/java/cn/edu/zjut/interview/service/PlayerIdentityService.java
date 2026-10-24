package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.PlayerIdentity;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.PlayerIdentityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 「我的身份」（对齐 {@code game_system/player_identity/mod.rs} 的读写部分）。
 *
 * <p>本批只落「当前身份是谁」这一件事，因为它是身份解耦的地基：
 * 玩家台词落库时要能写下"这是哪个身份说的、叫什么名字"，
 * 否则多人场景下历史里分不清谁在说话。
 *
 * <p>关系解析与两块注入（{@code build_player_block} / {@code build_peers_block}）在
 * {@link PlayerIdentityPrompt}。
 */
@Service
public class PlayerIdentityService {

    /** 没建过任何身份时用的兜底身份 id。 */
    public static final String DEFAULT_IDENTITY_ID = "me";

    /** 当前身份不允许删除（本项目扩展，超出规格；原版没有这一条）。 */
    public static final String IDENTITY_IS_CURRENT = "IDENTITY_IS_CURRENT";

    /** 身份被面试记录引用，不允许删除（对齐 dev「有 N 个存档正在使用这个身份」）。 */
    public static final String IDENTITY_IN_USE = "IDENTITY_IN_USE";

    private final PlayerIdentityRepository repository;
    private final InterviewMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public PlayerIdentityService(PlayerIdentityRepository repository,
                                 InterviewMessageRepository messageRepository,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /** 删除被拒：带错误码与提示文案（控制器按码回 409）。 */
    public static class IdentityDeleteBlocked extends RuntimeException {

        private final String code;

        public IdentityDeleteBlocked(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 当前生效的身份；没设过返回 {@link Optional#empty()}（调用方自行决定要不要兜底）。 */
    public Optional<PlayerIdentity> current() {
        return repository.findFirstByIsCurrentTrue();
    }

    /**
     * 当前身份的显示名；没有身份时回落「用户」。
     *
     * <p>玩家台词落 {@code display_name} 走这里，保证每条玩家台词都带得出一个名字。
     */
    public String currentDisplayName() {
        return current().map(PlayerIdentity::displayName).orElse(PlayerIdentity.DEFAULT_NAME);
    }

    /** 当前身份 id；没有身份时返回 {@code null}（老库、没建过身份的历史数据）。 */
    public String currentIdentityId() {
        return current().map(PlayerIdentity::getIdentityId).orElse(null);
    }

    /** 列出全部身份（切换用，按 id 升序）。 */
    public java.util.List<PlayerIdentity> list() {
        return repository.findAll().stream()
                .sorted(java.util.Comparator.comparing(PlayerIdentity::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /** 切换当前身份：先把别的都置否，再置这一个（同一时刻只有一个生效）。 */
    @Transactional
    public Optional<PlayerIdentity> switchTo(String identityId) {
        Optional<PlayerIdentity> target = repository.findByIdentityId(identityId);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        for (PlayerIdentity each : repository.findAll()) {
            boolean shouldBeCurrent = each.getId().equals(target.get().getId());
            if (!Boolean.valueOf(shouldBeCurrent).equals(each.getIsCurrent())) {
                each.setIsCurrent(shouldBeCurrent);
                each.setUpdatedAt(LocalDateTime.now());
                repository.save(each);
            }
        }
        return target;
    }

    /** 按身份 id 取详情（关系键 {@code me:<身份 id>} 用的就是它）。 */
    public Optional<PlayerIdentity> get(String identityId) {
        if (identityId == null || identityId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByIdentityId(identityId.trim());
    }

    /**
     * 新建 / 覆盖保存一张身份卡（对齐 {@code IdentityStore::save}）。
     *
     * <p>{@code name} / {@code subtitle} 先 trim，<b>prompt 与 relations 不 trim</b>；
     * id 为空则生成新的 UUID。同 id 重复保存 = 覆盖。
     */
    @Transactional
    public PlayerIdentity save(PlayerIdentity form) {
        PlayerIdentity card = form == null ? new PlayerIdentity() : form;
        String id = card.getIdentityId() == null ? "" : card.getIdentityId().trim();
        if (id.isEmpty()) {
            card.setIdentityId(UUID.randomUUID().toString());
        } else {
            card.setIdentityId(id);
            // 覆盖保存时沿用原行主键，避免派生出一行重复的 identity_id
            repository.findByIdentityId(id).ifPresent(existing -> card.setId(existing.getId()));
        }
        card.setName(card.getName() == null ? "" : card.getName().trim());
        card.setSubtitle(card.getSubtitle() == null ? "" : card.getSubtitle().trim());
        if (card.getRelationMap() != null) {
            card.setRelations(serializeRelations(card.getRelationMap()));
            card.setRelationMap(null);
        } else if (card.getRelations() != null) {
            card.setRelations(serializeRelations(card.getRelations()));
        } else {
            card.setRelations("{}");
        }
        card.setUpdatedAt(LocalDateTime.now());
        if (card.getIsCurrent() == null) {
            card.setIsCurrent(Boolean.FALSE);
        }
        return repository.save(card);
    }

    /**
     * 删除身份卡（对齐 {@code api_player_identity.rs:114}）。
     *
     * <p>先按引用查：{@code interview_message.identity_id = <id>} 有任意行即视为被引用
     * （{@code interview_session} 没有 identity_id 列，不查它）→ 报
     * 「有 N 个面试记录正在使用这个身份，无法删除。」。
     *
     * <p>未被引用就硬删 —— 原版是移入 {@code .trash/} 的软删除，H2 没有回收站，
     * 属实现方式差异。另加护栏：当前身份不允许删（本项目扩展）。
     */
    @Transactional
    public void delete(String identityId) {
        PlayerIdentity card = get(identityId).orElseThrow(
                () -> new IdentityDeleteBlocked("IDENTITY_NOT_FOUND", "身份不存在"));
        if (Boolean.TRUE.equals(card.getIsCurrent())) {
            throw new IdentityDeleteBlocked(IDENTITY_IS_CURRENT, "当前身份不可删除，请先切换到别的身份。");
        }
        long used = messageRepository.countByIdentityId(card.getIdentityId());
        if (used > 0) {
            throw new IdentityDeleteBlocked(IDENTITY_IN_USE,
                    "有 " + used + " 个面试记录正在使用这个身份，无法删除。");
        }
        repository.delete(card);
    }

    /** 身份卡上的关系 Map（解析 CLOB 里的 JSON 对象）。 */
    public Map<String, String> relationsOf(String identityId) {
        return get(identityId).map(card -> parseRelations(card.getRelations()))
                .orElseGet(LinkedHashMap::new);
    }

    /**
     * 把编辑器传来的关系写成 JSON 字符串存 CLOB（对齐原版 {@code relations: HashMap} 的落盘形态）。
     *
     * <p>值是 JSON 对象文本就原样存，是 Map 就序列化；坏数据按空表处理，不阻断保存。
     */
    private String serializeRelations(Object relations) {
        if (relations == null) {
            return "{}";
        }
        if (relations instanceof CharSequence text) {
            String raw = text.toString().trim();
            return raw.isEmpty() ? "{}" : text.toString();
        }
        try {
            return objectMapper.writeValueAsString(relations);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, String> parseRelations(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = json == null ? "" : json.trim();
        if (raw.isEmpty()) {
            return out;
        }
        try {
            var node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                return out;
            }
            node.properties().forEach(entry -> {
                String value = entry.getValue() == null ? null : entry.getValue().asText();
                if (value != null && !value.isBlank()) {
                    out.put(entry.getKey(), value);
                }
            });
        } catch (Exception e) {
            // 坏数据按无关系处理：关系是锦上添花，不该让整张卡读不出来
        }
        return out;
    }
}
