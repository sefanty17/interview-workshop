package cn.edu.zjut.interview.editor;

import cn.edu.zjut.interview.dto.schema.ActionSpecView;
import cn.edu.zjut.interview.dto.schema.ConditionSyntaxView;
import cn.edu.zjut.interview.dto.schema.EventSpecView;
import cn.edu.zjut.interview.dto.schema.FieldSpecView;
import cn.edu.zjut.interview.dto.schema.ScriptSchemaView;
import cn.edu.zjut.interview.dto.schema.UnlockConditionSpecView;

import java.util.ArrayList;
import java.util.List;

/**
 * 剧本事件 schema —— 17 种事件及其全部字段的单一真相源。
 *
 * <p>1:1 迁移自 {@code script_engine/schema.rs}（原版 565 行）。改引擎时必须同步改这个文件。
 *
 * <p>词表归属（与原版注释一致）：
 * <ul>
 *   <li>情绪：由前端拥有（情绪→立绘映射在前端），这里只标 {@code kind: "emotion"}；</li>
 *   <li>章节名：每个剧本自己的，前端从已加载章节列表填；</li>
 *   <li>素材文件名：前端从素材索引填；</li>
 *   <li>角色：{@code MAIN} 加剧本内 NPC 目录名；</li>
 *   <li>背景特效：由后端拥有（{@link #KNOWN_EFFECTS}），因为它对应前端组件是否存在。</li>
 * </ul>
 */
public final class ScriptSchemaCatalog {

    private ScriptSchemaCatalog() {
    }

    /** 内置背景特效清单，1:1 抄自 {@code events/known_effects.rs:17-30}。大小写敏感。 */
    public static final List<String> KNOWN_EFFECTS = List.of(
            "StarField", "Sakura", "Fireworks", "BA", "Fireflies", "MeteorShower",
            "Drizzle", "Rain", "Thunderstorm", "Snow", "Blizzard", "Fog");

    // ============================================================
    // 构造
    // ============================================================

    public static ScriptSchemaView build() {
        return new ScriptSchemaView(
                events(),
                commonFields(),
                storyConfigFields(),
                actionTypes(),
                unlockConditionTypes(),
                // 与 events_handler.rs 的 replace_placeholder 覆盖范围一致
                List.of("text", "prompt", "hint", "end_line", "dialog_prompt",
                        "end_prompt", "content", "description"),
                new ConditionSyntaxView(
                        List.of("var == 值", "var != 值", "var（真值判断）"),
                        List.of(">", "<", ">=", "<=", "&&", "||", "!", "括号", "算术"),
                        "比较是按文字逐个比对的。没赋过值的变量不会正常运作，先用「设置变量」给它赋个值再比较。"
                                + "注意「大于/小于」这类比较不支持：写 hp >= 5 不会报错，但会被当成一个名叫 "
                                + "\"hp >= 5\" 的变量去查，结果不会正常运作。"));
    }

    private static List<EventSpecView> events() {
        List<EventSpecView> list = new ArrayList<>();

        // ---------- 叙事 ----------
        list.add(new EventSpecView("narration", "旁白", "叙事", "#94a3b8", List.of(
                field("text", "旁白文本", "textarea").required()
                        .hint("多行会逐行依次显示，空行被跳过").build(),
                field("displayName", "说话人标签", "text").placeholder("旁白").build())));

        list.add(new EventSpecView("player", "玩家台词", "叙事", "#38bdf8", List.of(
                field("text", "台词", "textarea").required().build(),
                field("displayName", "显示名", "text").placeholder("（跟随玩家名）").build())));

        list.add(new EventSpecView("dialogue", "AI台词", "叙事", "#a78bfa", List.of(
                characterField(),
                field("text", "台词", "textarea").required()
                        .hint("想让这句话真正「说」出来，必须同时满足两个条件：1) 台词最开头用【开心】【难过】等情绪标注；"
                                + "2) 所选角色已在「角色设置」里开启语音。缺一不可，否则只会显示文字、不会发声。"
                                + "例：【开心】今天能见到你，我真的很高兴！").build(),
                emotionField(),
                field("displayName", "显示名", "text").build(),
                field("displaySubtitle", "副标题", "text").build())));

        // ---------- AI ----------
        list.add(new EventSpecView("ai_dialogue", "AI 对话", "AI", "#e879f9", List.of(
                characterField(),
                field("prompt", "剧情提示", "textarea")
                        .hint("以旁白身份注入上下文引导 AI；留空则纯靠已有台词生成。注意提示会留在上下文里累积")
                        .build())));

        list.add(new EventSpecView("free_dialogue", "自由对话", "AI", "#f472b6", List.of(
                characterField(),
                field("hint", "输入框提示", "text").placeholder("自由对话...").build(),
                field("max_rounds", "最大轮数", "number").placeholder("-1")
                        .hint("留空或 ≤0 表示不限轮数，此时唯一出口是玩家输入包含结束语").build(),
                field("end_line", "结束语", "text").placeholder("结束")
                        .hint("玩家输入里出现这个文字就会结束对话（比如「结束」）").build(),
                field("prompt", "每轮剧情提示", "textarea").build(),
                field("end_prompt", "末轮剧情提示", "textarea").build())));

        // ---------- 交互 ----------
        list.add(new EventSpecView("choices", "选项", "交互", "#818cf8", List.of(
                field("options", "选项列表", "choice_options").required()
                        .hint("顺序即优先级；不带文案的选项匹配任意输入，必须放最后").build(),
                field("allow_free", "允许自由输入", "bool").defaultDesc("false")
                        .hint("开启后玩家可以在输入框里直接打字作答").build())));

        list.add(new EventSpecView("input", "等待输入", "交互", "#60a5fa", List.of(
                field("hint", "输入框提示", "text").placeholder("请输入...")
                        .hint("不填时输入框显示默认提示「请输入...」").build())));

        // ---------- 流程 ----------
        list.add(new EventSpecView("set_variable", "设置变量", "流程", "#f87171", List.of(
                field("options", "赋值组", "var_options").required()
                        .hint("每组可带条件；与 choices 不同，这里所有满足条件的组都会执行").build())));

        list.add(new EventSpecView("chapter_end", "章节结束", "流程", "#e2e8f0", List.of(
                field("end_type", "结束方式", "select").required()
                        .options("linear", "branching", "ai_judged")
                        .hint("linear 直接跳转；branching 按条件分支；ai_judged 交给 LLM 判断").build(),
                field("next_chapter", "下一章", "chapter")
                        .hint("仅 linear 使用；选「剧本结束」即整个剧本结束").build(),
                field("options", "分支", "branch_options")
                        .hint("branching / ai_judged 使用；顺序即优先级，可设一个 default 兜底").build(),
                field("prompt", "AI 判定提示", "textarea").hint("仅 ai_judged 使用").build(),
                // 只展示不给编辑。next 在引擎里优先级高于 next_chapter，两个都能填的话，
                // 作者改了「下一章」却不生效 —— 这是最难自己看出来的一类问题。
                field("next", "下一章（旧字段）", "deprecated")
                        .disabled("引擎里它的优先级高于「下一章」。老剧本才有，新剧本请只用上面那个").build())));

        // ---------- 演出 ----------
        list.add(new EventSpecView("modify_character", "角色调整", "演出", "#fbbf24", List.of(
                characterField(),
                field("action", "动作", "select")
                        .options("show_character", "hide_character")
                        .optionLabels("show_character（显示角色）", "hide_character（隐藏角色）").build(),
                emotionField(),
                field("clothes", "服装", "text")
                        .hint("对应 avatar/<服装>/ 子目录；留空或 default 表示不进子目录").build(),
                field("perceive", "能否听到后续台词", "bool").defaultDesc("保持当前状态")
                        .hint("决定该角色是否出现在后续台词的「感知者」列表里。"
                                + "注意 hide_character 会同时把角色移出感知列表").build())));

        list.add(new EventSpecView("background", "背景", "演出", "#34d399", List.of(
                field("imagePath", "背景图", "asset").required().assetKind("background").build(),
                field("transition", "过渡时长（秒）", "number").placeholder("1.0").build())));

        list.add(new EventSpecView("background_effect", "背景特效", "演出", "#2dd4bf", List.of(
                field("effect", "特效", "select").required()
                        .options(effectOptions())
                        .hint("从下拉里选；选「无特效」会清空当前特效").build())));

        list.add(new EventSpecView("present_pic", "插图", "演出", "#a3e635", List.of(
                field("imagePath", "图片", "asset").required().assetKind("pic").build(),
                field("scale", "缩放", "number").placeholder("1.0").build())));

        // ---------- 声音 ----------
        list.add(new EventSpecView("music", "背景音乐", "声音", "#fb923c", List.of(
                field("musicPath", "音乐", "asset").required().assetKind("music").build(),
                field("playbackSpeed", "播放速度", "number")
                        .hint("1.0 = 原速；留空同 1.0。范围建议 0.5–2.0，超出可能失真").build())));

        list.add(new EventSpecView("sound", "音效", "声音", "#facc15", List.of(
                field("soundPath", "音效", "asset").required().assetKind("sound").build())));

        list.add(new EventSpecView("ambient", "环境音", "声音", "#22d3ee", List.of(
                // 刻意不 required：开了「停止该轨」时留空正是「停掉全部轨道」的写法。
                field("ambientPath", "环境音", "asset").assetKind("ambient")
                        .hint("播放时必填；配合下面的「停止该轨」留空表示停掉全部环境音").build(),
                field("volume", "音量", "number").placeholder("100").hint("0–100").build(),
                field("loop", "循环", "bool").defaultDesc("true").build(),
                field("stop", "停止该轨", "bool").defaultDesc("false")
                        .hint("开启时会淡出停止；环境音留空则停止全部轨道").build(),
                field("fade", "淡入淡出", "bool").defaultDesc("true").build())));

        // ---------- 成就 ----------
        list.add(new EventSpecView("unlock_achievement", "解锁成就", "成就", "#fbbf24", List.of(
                field("achievement_id", "成就键名", "text").required().placeholder("如：summer_star")
                        .hint("给这个成就起的英文标识，不能与内置成就或本剧本其他成就重名（校验器会提示）").build(),
                field("title", "成就标题", "text").required().placeholder("如：夏日之星")
                        .hint("玩家在成就列表里看到的成就名字").build(),
                field("description", "成就描述", "textarea").required()
                        .hint("达成条件说明，展示给玩家看").build())));

        return List.copyOf(list);
    }

    private static List<FieldSpecView> commonFields() {
        return List.of(
                field("condition", "触发条件", "condition")
                        .hint("设置条件后，只有满足条件时本事件才会执行；留空则必定触发").build(),
                field("duration", "事件间隔（秒）", "number").placeholder("留空或负数 = 等玩家点击")
                        .hint("事件展示后自动等待 N 秒再继续，作为事件之间的 CD；留空或填负数表示等玩家点击后才继续")
                        .build());
    }

    private static List<FieldSpecView> storyConfigFields() {
        return List.of(
                field("script_name", "剧本名", "text").required()
                        .hint("全局唯一，重名会导致其中一个剧本在列表里被覆盖").build(),
                field("description", "简介", "textarea").build(),
                field("recommand_start", "推荐开始时机", "text").placeholder("例如：好感度达到 30 之后")
                        .hint("展示给玩家看的推荐时机说明，仅作展示，不影响剧情判断").build(),
                field("intro_chapter", "开场章节", "chapter").required().build());
    }

    private static List<ActionSpecView> actionTypes() {
        return List.of(
                new ActionSpecView("add_line", "追加一句玩家台词", "以玩家名义写入对话历史，AI 能看到",
                        List.of("choices")),
                new ActionSpecView("set_var", "设置变量", "表达式形如 flag = true / count += 1 / hp -= 5",
                        List.of("choices", "set_variable")));
    }

    private static List<UnlockConditionSpecView> unlockConditionTypes() {
        return List.of(
                new UnlockConditionSpecView("chat_count", "累计聊天条数达到", List.of(
                        field("threshold", "条数", "number").required().build())),
                new UnlockConditionSpecView("time_range", "处于时间段内", List.of(
                        field("start_hour", "起始小时", "number").required().build(),
                        field("end_hour", "结束小时", "number").required()
                                .hint("起始大于结束表示跨零点").build())),
                new UnlockConditionSpecView("adventure_completed", "已完成某个羁绊冒险", List.of(
                        field("adventure_folder", "剧本目录名", "text").required()
                                .hint("填目标剧本的目录名（不是显示名）").build())),
                new UnlockConditionSpecView("achievement_unlocked", "已解锁某个成就", List.of(
                        field("achievement_id", "成就 id", "text").required().build())));
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static FieldBuilder field(String key, String label, String kind) {
        return new FieldBuilder(key, label, kind);
    }

    private static FieldSpecView characterField() {
        return field("character", "角色", "character").required()
                .hint("MAIN = 当前选中的主角；其余为本剧本 characters/ 下的目录名").build();
    }

    private static FieldSpecView emotionField() {
        return field("emotion", "情绪", "emotion").hint("表外的值会回落成「正常」").build();
    }

    private static List<String> effectOptions() {
        List<String> v = new ArrayList<>();
        v.add("None");
        v.addAll(KNOWN_EFFECTS);
        return List.copyOf(v);
    }

    /** 链式构造器，对应原版 {@code FieldSpec::new(..).required().hint(..)} 那一套。 */
    private static final class FieldBuilder {
        private final String key;
        private final String label;
        private final String kind;
        private boolean required;
        private String assetKind;
        private List<String> options = List.of();
        private List<String> optionLabels = List.of();
        private String placeholder;
        private String defaultDesc;
        private String hint;
        private boolean enabled = true;

        private FieldBuilder(String key, String label, String kind) {
            this.key = key;
            this.label = label;
            this.kind = kind;
        }

        FieldBuilder required() {
            this.required = true;
            return this;
        }

        FieldBuilder hint(String h) {
            this.hint = h;
            return this;
        }

        FieldBuilder placeholder(String p) {
            this.placeholder = p;
            return this;
        }

        FieldBuilder defaultDesc(String d) {
            this.defaultDesc = d;
            return this;
        }

        FieldBuilder options(String... opts) {
            this.options = List.of(opts);
            return this;
        }

        FieldBuilder options(List<String> opts) {
            this.options = List.copyOf(opts);
            return this;
        }

        FieldBuilder optionLabels(String... labels) {
            this.optionLabels = List.of(labels);
            return this;
        }

        FieldBuilder assetKind(String k) {
            this.assetKind = k;
            return this;
        }

        FieldBuilder disabled(String why) {
            this.enabled = false;
            this.hint = why;
            return this;
        }

        FieldSpecView build() {
            return new FieldSpecView(key, label, kind, required, assetKind, options,
                    optionLabels, placeholder, defaultDesc, hint, enabled);
        }
    }
}
