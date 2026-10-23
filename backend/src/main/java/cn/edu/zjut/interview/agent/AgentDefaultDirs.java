package cn.edu.zjut.interview.agent;

/** 设置面板展示的默认目录（对齐原版 {@code AgentDefaultDirs}）。 */
public record AgentDefaultDirs(
        /** 数据根目录（原版 {@code data_dir::get_data_dir()}）。 */
        String dataDir,
        /** 技能库目录（固定 {@code game_data/skills}）。 */
        String skillsDir,
        /** 解析后的沙箱根目录（未配置时等于 dataDir）。 */
        String sandboxDir) {
}
