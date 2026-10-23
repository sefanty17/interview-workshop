package cn.edu.zjut.interview.config;

import java.util.List;

/**
 * 配置树，对应原版 {@code config/types.rs} 的 {@code ConfigTree}：
 * 分类 → 子分类 → 设置项。
 *
 * <p>原版分类用 {@code BTreeMap}（按名字排序），本项目把两层都拍平成有序列表，
 * 顺序由 {@link ConfigService} 的 {@code TREE_SPEC} 固定：
 * {@code llm/advanced}、{@code features/{chat-enhance, interface, chat-rhythm, audio}}，
 * 免得前端每次都按字符串排序把「功能设置」排到「LLM 配置」前面。
 *
 * @param categories 分类列表，顺序即前端渲染顺序
 */
public record ConfigTree(List<Category> categories) {

    /**
     * 一个顶层分类。
     *
     * @param id            分类 id（{@code llm / features}）
     * @param label         中文名（前端 MenuItem 标题）
     * @param subcategories 子分类列表，顺序即渲染顺序
     */
    public record Category(String id, String label, List<Subcategory> subcategories) {
    }

    /**
     * 一个子分类（原版 {@code Subcategory}：{@code description + settings}），
     * 这里把 {@code description} 换成前端要显示的 {@code label}。
     *
     * @param id       子分类 id（{@code advanced / chat-enhance / interface / chat-rhythm / audio}）
     * @param label    中文名
     * @param settings 组内设置项，顺序即注册顺序
     */
    public record Subcategory(String id, String label, List<ConfigSetting> settings) {
    }
}
