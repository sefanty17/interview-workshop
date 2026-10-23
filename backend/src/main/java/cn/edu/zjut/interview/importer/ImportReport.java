package cn.edu.zjut.interview.importer;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 覆盖度报告（规格 B5-BE §2.5）：逐事件给出 green / yellow / red 三色结论。
 *
 * <p>语义：
 * <ul>
 *   <li>green —— 原样能跑（可能做过等价的字段映射，note 里写清楚映射了什么）</li>
 *   <li>yellow —— 能跑但会走样：资源没映射、角色没定义、缺必需字段</li>
 *   <li>red —— 跑不了：事件类型未实现、章节引用悬空</li>
 * </ul>
 *
 * <p>报告是导入的<b>产物</b>而不是副作用：黄/红项不会被修掉，只会被写下来。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportReport(
        String scriptCode,
        String scriptName,
        int chapters,
        int events,
        List<Item> green,
        List<Item> yellow,
        List<Item> red,
        Summary summary) {

    /**
     * 一条事件级结论。
     *
     * <p>{@code index} 是事件在章节 events 数组里的下标（从 0 起）；剧本级问题（如
     * {@code intro_chapter} 指向不存在的章节）用 {@code index = -1}、{@code chapter = ""}。
     * green 只填 {@code note}，yellow/red 只填 {@code issue} + {@code detail}，
     * 空字段靠 {@code NON_NULL} 从 JSON 里省掉。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String chapter,
            int index,
            String type,
            String note,
            String issue,
            String detail) {

        public static Item green(String chapter, int index, String type, String note) {
            return new Item(chapter, index, type, note, null, null);
        }

        public static Item yellow(String chapter, int index, String type, String issue, String detail) {
            return new Item(chapter, index, type, null, issue, detail);
        }

        public static Item red(String chapter, int index, String type, String issue, String detail) {
            return new Item(chapter, index, type, null, issue, detail);
        }
    }

    /** 三色计数，前端顶部按这个显示「18 可执行 / 4 需处理 / 1 未实现」。 */
    public record Summary(int green, int yellow, int red) {
    }
}
