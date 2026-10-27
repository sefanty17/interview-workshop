package cn.edu.zjut.interview.agent;

import java.util.List;

/**
 * AI 助手的四个角色（对齐 {@code skill_agent/role.rs::Role}）。
 *
 * <p>为什么分成四个而不是一个：一个模型同时记住「编剧要天马行空」「落盘要严格照字段填」
 * 「校验要苛刻挑错」三套互相矛盾的要求，会互相妥协；手册越堆越长，讲到后面就忘了前面。
 * 拆开后每个角色只拿到一本薄手册，没有第二条路可走。
 *
 * <p>两条硬约束（原版明确设计，别"优化"掉）：
 * <ol>
 *   <li><b>本角色唯一预注入的手册</b>——只注入自己那本 {@link #doc()}，流程总纲另行注入。</li>
 *   <li><b>都不给 {@code read_skill}</b>——模型没有主动读别的手册的途径，
 *       也就不知道"还有别的路可以走"，不会在写剧本时偷偷跑去查落盘规范。</li>
 * </ol>
 *
 * <p>工具是封闭集合：不在 {@link #tools()} 里的就算硬调也会被拒。
 */
public enum Role {

    /** 编剧：写大纲、写正文（小说式全文）。 */
    WRITER("编剧", "script-writer/SKILL.md"),
    /** 素材官：清点素材与角色卡，算缺口。 */
    DEMANDER("素材官", "script-demander/SKILL.md"),
    /** 落盘：把剧情稿转成能跑的章节事件。 */
    TRANSFORMER("落盘", "script-transformer/SKILL.md"),
    /** 校验：只读体检，给建议改法。 */
    OPTIMIZER("校验", "script-optimizer/SKILL.md");

    private final String label;
    private final String doc;

    Role(String label, String doc) {
        this.label = label;
        this.doc = doc;
    }

    public String label() {
        return label;
    }

    /** 本角色唯一预注入的手册（相对技能库目录）。 */
    public String doc() {
        return doc;
    }

    /**
     * 本角色能用的工具，封闭集合。
     *
     * <p>{@code search_knowledge}（K7 扩展）四角色都给：它只读岗位语料、不碰文件，
     * 而四个角色写的东西都与技术事实有关，能查就该能查。
     */
    public List<String> tools() {
        return switch (this) {
            case WRITER, DEMANDER -> List.of(
                    "list_files", "read_file", "write_file", "edit_file", "search_knowledge");
            // 落盘改章节时要能整份删掉一章，也要能自检格式
            case TRANSFORMER -> List.of(
                    "list_files", "read_file", "write_file", "edit_file",
                    "delete_file", "validate_script", "search_knowledge");
            // 校验只读：连 write_file 都不给，"这一轮一个字都没写"才是真的
            case OPTIMIZER -> List.of(
                    "list_files", "read_file", "validate_script", "search_knowledge");
        };
    }

    /** 是否只能读（决定这一轮能不能落笔）。 */
    public boolean readOnly() {
        return this == OPTIMIZER;
    }
}
