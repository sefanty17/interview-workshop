package cn.edu.zjut.interview.agent;

/** 技能内容（设置面板预览 SKILL.md 用），对齐原版 {@code SkillContent}。 */
public record SkillContent(
        String name,
        /** 技能所在目录。 */
        String baseDirectory,
        /** SKILL.md 全文。 */
        String content) {
}
