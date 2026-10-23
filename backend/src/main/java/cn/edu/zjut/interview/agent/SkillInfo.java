package cn.edu.zjut.interview.agent;

/** 发现的技能（对齐原版 {@code skill_agent/skills.rs} 的 {@code SkillInfo}）。 */
public record SkillInfo(
        /** 技能目录名，同时是 {@code GET /api/editor/agent/skills/{name}} 的键。 */
        String name,
        /** SKILL.md frontmatter 里的 description。 */
        String description,
        /** {@code "project"} 或 {@code "global"}。 */
        String location,
        /** 技能目录的绝对路径。 */
        String path) {
}
