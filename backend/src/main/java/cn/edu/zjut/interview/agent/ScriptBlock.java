package cn.edu.zjut.interview.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 「当前剧本」段（1:1 迁移自 {@code history.rs:10-36 build_script_block}）。
 *
 * <p>只给 key / 路径，并指示 agent 先看已有内容（实时读取，不注入静态快照）。整段由
 * {@code core.rs:99-104} 在<b>一轮开始前</b>算一次，然后接在 system prompt 的 base 之后
 * （{@code history.rs:124}：{@code base + script_block + skills_block + task_block}）。
 *
 * <p><b>路径口径保持原版</b>：模板里提到的
 * {@code <standalone 或 character/<角色>>/<剧本名>/story_config.yaml} 不改 ——
 * {@code script_block} 是原样注入，不经过 {@link RoleScope} 那层改写。这是原版行为。
 */
public final class ScriptBlock {

    /** {@code core_build_script_block.md} 原文（{@code include_str!} 的等价物）。 */
    private static final String TEMPLATE = load("/agent/prompts/core_build_script_block.md");

    private ScriptBlock() {
    }

    /**
     * 构建「当前剧本」段。
     *
     * @param sandboxDir 文件沙箱根（原版 {@code ctx.sandbox_dir}）
     * @param scriptKey  会话绑定的剧本 key；{@code null} / 空 = 还没打开剧本
     * @param knownKeys  磁盘上现有的剧本包（只用在没绑 key 的那一支）
     */
    static String build(Path sandboxDir, String scriptKey, List<String> knownKeys) {
        if (scriptKey == null || scriptKey.isBlank()) {
            String out = TEMPLATE.strip();
            if (knownKeys != null && !knownKeys.isEmpty()) {
                out = out + "\n\n（磁盘上现有的剧本包，仅供你告诉用户「有这些」："
                        + String.join("、", knownKeys) + "）";
            }
            return out;
        }
        Path dir = SkillAgentService.scriptDir(sandboxDir, scriptKey);
        // 原版 resolve_script_dir 失败 → String::new()：剧本缺失/失效 → 降级，不阻断对话
        if (dir == null || !Files.exists(dir)) {
            return "";
        }
        String rel = relativeOf(sandboxDir, dir);
        return "\n\n【当前剧本上下文】\n剧本 key：" + scriptKey
                + "\n剧本目录：" + rel + "（相对于文件沙箱根 " + sandboxDir + "）"
                + "\n\n工作之前，请先用 list_files / read_file 查看剧本中已有的内容，再决定如何编写或修改。"
                + "\n剧本中的素材引用（imagePath / musicPath / soundPath / ambientPath）只写素材文件名本身"
                + "（如 夜晚.webp），不要带 backgrounds/、musics/ 等类型目录前缀；引擎会按事件类型自动到对应目录查找。";
    }

    /** {@code dir.strip_prefix(sandbox_dir).unwrap_or(&dir)}，路径分隔符一律 {@code /}。 */
    private static String relativeOf(Path sandboxDir, Path dir) {
        Path rel;
        try {
            rel = sandboxDir == null ? dir : (dir.startsWith(sandboxDir) ? sandboxDir.relativize(dir) : dir);
        } catch (IllegalArgumentException e) {
            rel = dir;
        }
        return rel.toString().replace('\\', '/');
    }

    private static String load(String resource) {
        try (InputStream in = ScriptBlock.class.getResourceAsStream(resource)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
