package cn.edu.zjut.interview.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 玩家身份的提示词注入（1:1 迁移自
 * {@code game_system/player_identity/resolve.rs}）。
 *
 * <p>两块内容，用途不同、**不能混**：
 * <ul>
 *   <li>{@link #buildPlayerBlock} —— 「正在与你对话的那个『我』是谁」，含名字、基础设定、
 *       以及与这个角色的关系。声明优先级高于人设与历史。</li>
 *   <li>{@link #buildPeersBlock} —— 「你眼里的其他角色」，只在多人场景有内容。
 *       <b>不做提示词兜底</b>：只注入显式写过关系的对象，否则每个角色都会把别人的
 *       公开人设当成"我对 ta 的看法"，等于凭空编关系。</li>
 * </ul>
 *
 * <p>关系解析三级优先（{@code resolve_relation}）：说话者卡写的关系 → 对方卡写的关系
 * → 目标提示词兜底；取到即停。前两级的区别很重要 —— 第一级是"我真心这么想"，
 * 第二级是"ta 这么说（未必是事实）"，注入时的措辞完全不同。
 */
public class PlayerIdentityPrompt {

    private static final Logger log = LoggerFactory.getLogger(PlayerIdentityPrompt.class);

    private final ObjectMapper objectMapper;

    public PlayerIdentityPrompt(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 关系端点：一个 AI 角色，或一个玩家身份。 */
    public sealed interface Endpoint permits AiEndpoint, MeEndpoint {

        /** 关系表里用的键：{@code ai:<角色code>} / {@code me:<身份id>}。 */
        String key();
    }

    /** 某个 AI 角色（键用角色文件夹名 / code）。 */
    public record AiEndpoint(String folder) implements Endpoint {
        @Override
        public String key() {
            return "ai:" + folder;
        }
    }

    /** 某个玩家身份。 */
    public record MeEndpoint(String id) implements Endpoint {
        @Override
        public String key() {
            return "me:" + id;
        }
    }

    /** 这段关系是从哪儿取的，决定注入时的措辞。 */
    public enum RelationSource {
        /** 说话者自己卡上写的 —— "我真心这么想"。 */
        SPEAKER,
        /** 对方卡上写的 —— "ta 这么说，未必是事实"。 */
        COUNTERPARTY,
        /** 目标提示词兜底。 */
        PROMPT_FALLBACK
    }

    /** 解析结果：关系文本 + 出处。 */
    public record RelationView(String text, RelationSource source) {
    }

    /**
     * 解析「说话者眼中的目标」：按说话者卡、对方卡、目标提示词的顺序取，取到即停。
     *
     * @param speakerRelations 说话者卡上的关系表
     * @param targetRelations  对方卡上的关系表
     * @param targetPrompt     目标提示词（最后一级兜底，可为空）
     */
    public Optional<RelationView> resolveRelation(Endpoint speaker, Endpoint target,
                                                  Map<String, String> speakerRelations,
                                                  Map<String, String> targetRelations,
                                                  String targetPrompt) {
        String fromSpeaker = trimToNull(speakerRelations == null ? null : speakerRelations.get(target.key()));
        if (fromSpeaker != null) {
            return Optional.of(new RelationView(fromSpeaker, RelationSource.SPEAKER));
        }
        String fromTarget = trimToNull(targetRelations == null ? null : targetRelations.get(speaker.key()));
        if (fromTarget != null) {
            return Optional.of(new RelationView(fromTarget, RelationSource.COUNTERPARTY));
        }
        String prompt = trimToNull(targetPrompt);
        if (prompt != null) {
            return Optional.of(new RelationView(prompt, RelationSource.PROMPT_FALLBACK));
        }
        return Optional.empty();
    }

    /**
     * 「本局必须遵守：正在与你对话的『我』」块（对齐 {@code build_player_block}）。
     *
     * <p>全空（没名字、没设定、没关系）时返回空串 —— 不能往 prompt 里塞一个空壳标题，
     * 那会让模型以为有个"我"却什么都不知道。
     *
     * @param name     玩家显示名
     * @param prompt   玩家的基础设定（对所有角色公开）
     * @param relation 这个角色与玩家的关系（可为空）
     */
    public String buildPlayerBlock(String name, String prompt, RelationView relation) {
        String safeName = trimToNull(name);
        String safePrompt = trimToNull(prompt);
        boolean hasRelation = relation != null && trimToNull(relation.text()) != null;

        if (safeName == null && safePrompt == null && !hasRelation) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        out.append("\n\n【本局必须遵守：正在与你对话的「我」】\n");
        out.append("（本节优先于上面人设、历史台词与记忆里关于这个人的任何写法；")
                .append("因为此处相当于现实的基础设定，所以两者冲突时以本节为主）\n");

        if (safeName != null) {
            out.append("名字：").append(safeName).append('\n');
        }
        if (safePrompt != null) {
            out.append("我的基础设定（这是对所有角色都公开、所有角色都看得到的设定）：")
                    .append(safePrompt).append('\n');
        }

        // 只有「说话者自己写的」才当成态度注入；
        // 对方卡上写的（COUNTERPARTY）是 ta 的说法，不能当成"你和我的关系"塞给说话者
        boolean hasAttitude = false;
        if (hasRelation && relation.source() == RelationSource.SPEAKER) {
            out.append("你和我的关系（必须体现在称呼、语气、动作与你的反应里，不要只是知道）：")
                    .append(relation.text()).append('\n');
            out.append("关于关系：上面你和我的关系是我的基础设定以外的更为特殊的联系。\n");
            hasAttitude = true;
        }

        if (safeName != null || hasAttitude) {
            out.append('\n');
            if (safeName != null) {
                out.append("要求：在一般情况下，称呼 ta 时用「").append(safeName)
                        .append("」，该称呼优先于其他设定；");
            } else {
                out.append("要求：");
            }
            out.append("在合适的台词里可以适时体现这层态度；")
                    .append("不要复述本节内容，也不要提“设定 / 关系”这些字眼。\n");
        }
        return out.toString();
    }

    /** 场景里的另一个角色（{@code resolve.rs::ScenePeer}）。 */
    public record ScenePeer(String folder, String name, Map<String, String> relations) {
    }

    /**
     * 「你眼里的其他角色」块（对齐 {@code build_peers_block}）。
     *
     * <p><b>不做提示词兜底</b>：只注入显式写过关系的对象。若兜底，每个角色都会把
     * 别人的公开人设当成"我对 ta 的看法"，等于凭空编关系。
     */
    public String buildPeersBlock(Endpoint speaker, Map<String, String> speakerRelations,
                                  List<ScenePeer> peers) {
        if (peers == null || peers.isEmpty()) {
            return "";
        }
        StringBuilder lines = new StringBuilder();
        for (ScenePeer peer : peers) {
            Optional<RelationView> view = resolveRelation(speaker, new AiEndpoint(peer.folder()),
                    speakerRelations, peer.relations(), null);
            if (view.isEmpty()) {
                continue;
            }
            String text = switch (view.get().source()) {
                case SPEAKER -> "你对 ta 的看法：" + view.get().text();
                case COUNTERPARTY -> "ta 对你的说法（是 ta 的说法，未必是事实）：" + view.get().text();
                // 没有提示词兜底，这里不该出现；出现就跳过，别把公开人设当关系
                case PROMPT_FALLBACK -> null;
            };
            if (text == null) {
                continue;
            }
            lines.append("- ").append(peer.name() == null ? peer.folder() : peer.name())
                    .append("：").append(text).append('\n');
        }
        if (lines.length() == 0) {
            return "";
        }
        return "\n\n【你眼里的其他角色】\n（这些是你自己的认知，相关时体现即可，不必每句都提）\n"
                + lines;
    }

    /**
     * 把身份表里的 {@code relations}（JSON 对象）解析成 Map。
     *
     * <p>解析不了就当没有关系：关系是锦上添花，坏数据不该让整轮对话崩掉。
     */
    public Map<String, String> parseRelations(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = trimToNull(json);
        if (raw == null) {
            return out;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                return out;
            }
            node.properties().forEach(entry -> {
                String value = trimToNull(entry.getValue() == null ? null : entry.getValue().asText());
                if (value != null) {
                    out.put(entry.getKey(), value);
                }
            });
        } catch (Exception e) {
            log.warn("[PlayerIdentityPrompt] relations 不是合法 JSON，按无关系处理：{}", e.getMessage());
        }
        return out;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
