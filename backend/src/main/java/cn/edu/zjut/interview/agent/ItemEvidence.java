package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.agent.SkillAgentRouter.TaskKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 一项的物证核对：模型说做完了，代码自己去盘上 / 库里看产物在不在、有没有被改过。
 *
 * <p>1:1 迁移自原版 {@code stage/evidence.rs}（{@code artifact_exists} /
 * {@code entry_evidence} / {@code chapter_section_evidence}）与 {@code stage/queue.rs::is_judgeable}。
 * 调用点在 {@code core.rs:286-314}：每项工具循环跑完、正文拼进回复之前核一次，没看到产物就
 * 再喂一条 {@code 【代码核对】} 强制二跑。
 *
 * <p><b>判据是"和 before 不同"而不是"存在"</b>（原版 {@code existed_at_register} 的用意）：
 * 文件本来就在时，模型一个字没改也"存在"，按存在判定会放行空跑。
 *
 * <p>本项目的取数差异：原版章节就是 {@code Chapters/<id>.yaml}，本项目章节在
 * {@code interview_chapter} 表 —— 章节类的物证走 {@link ChapterLander#writtenChapterIds}，
 * 设计稿 / 细节稿 / 素材缺口表仍是沙箱里的 {@code .agent/*.md}。
 */
final class ItemEvidence {

    /** 素材缺口表相对剧本包根的路径（对齐 {@code stage::ASSETS_REL_PATH}）。 */
    static final String ASSETS_REL_PATH = ".agent/assets.md";

    private ItemEvidence() {
    }

    /** 这一项的产物在盘上/库里的物证快照；{@code exists=false} 时其余字段无意义。 */
    record Snapshot(boolean exists, long lastModifiedMillis, long size, String digest) {

        static final Snapshot ABSENT = new Snapshot(false, 0L, 0L, "");
    }

    /**
     * 拍快照（必须在 {@code runItemTools} <b>之前</b>拍）：多项时第 1 项的产物会被第 2 项误判。
     *
     * @param kind      本项任务类型
     * @param target    本项点名的对象（章节 id）
     * @param scriptDir 剧本包目录 {@code <sandbox>/scripts/<key>}；没有 key 时为 {@code null}
     *                  （由 {@code SkillAgentService.scriptDir} 统一推导，<b>不是</b>沙箱根）
     * @param scriptKey 会话绑定的剧本 code（只用来查库里的章节；{@code .agent/} 路径一律看 {@code scriptDir}）
     * @param lander    库里章节的读取入口
     */
    static Snapshot capture(TaskKind kind, String target, Path scriptDir, String scriptKey, ChapterLander lander) {
        if (kind == null) {
            return Snapshot.ABSENT;
        }
        return switch (kind) {
            case WRITE_CHAPTER, REVISE_CHAPTER -> chapterSnapshot(scriptDir, target);
            case OUTLINE, REVISE_OUTLINE -> fileSnapshot(agentFile(scriptDir, ScriptFacts.DESIGN_REL_PATH));
            case CHECK_ASSETS -> fileSnapshot(agentFile(scriptDir, ASSETS_REL_PATH));
            case DRAFT_CHAPTER -> fileSnapshot(agentFile(scriptDir, ScriptFacts.CHAPTER_DETAILS_REL_PATH));
            // Chat / Collect / Summarize / Polish 没有可核对产物
            default -> Snapshot.ABSENT;
        };
    }

    /**
     * 这一项真做出来了吗（原版 {@code entry_evidence} 的 {@code Evidence::Done}）。
     *
     * @param before    本项开跑前拍的快照
     * @param scriptDir 剧本包目录 {@code <sandbox>/scripts/<key>}（同上，不是沙箱根）
     * @param scriptKey 会话绑定的剧本 code（只用来查库里的章节）
     */
    static boolean produced(TaskKind kind, String target, Snapshot before, Path scriptDir,
                            String scriptKey, ChapterLander lander) {
        if (kind == null || !judgeable(kind, target)) {
            return false;
        }
        String key = chapterKey(target);
        Snapshot now = chapterSnapshot(scriptDir, target);
        return switch (kind) {
            // 落盘类：Chapters/ 下出现 target 才算做出来（新增）
            case WRITE_CHAPTER -> now.exists();
            // 改类：出现（改）或消失（删）二者之一都算做出来了
            case REVISE_CHAPTER -> now.exists() != (before != null && before.exists());
            case OUTLINE, REVISE_OUTLINE ->
                    textChanged(agentFile(scriptDir, ScriptFacts.DESIGN_REL_PATH), before);
            case CHECK_ASSETS -> textChanged(agentFile(scriptDir, ASSETS_REL_PATH), before);
            case DRAFT_CHAPTER -> {
                Path draft = agentFile(scriptDir, ScriptFacts.CHAPTER_DETAILS_REL_PATH);
                yield textChanged(draft, before) && titled(draft, key);
            }
            default -> false;
        };
    }

    /**
     * 这一类的"做完"有没有物证可核对（逐字对齐 {@code queue.rs:170-178}）：
     * 章节类要求目标非空，大纲 / 素材盘点恒可核对，其余（纯对话类）不核对。
     */
    static boolean judgeable(TaskKind kind, String target) {
        if (kind == null) {
            return false;
        }
        return switch (kind) {
            case WRITE_CHAPTER, REVISE_CHAPTER, DRAFT_CHAPTER -> target != null && !target.trim().isEmpty();
            case OUTLINE, REVISE_OUTLINE, CHECK_ASSETS -> true;
            default -> false;
        };
    }

    /**
     * 登记那一刻这一项的产物在不在盘上（只看有没有，不看内容）——对齐 {@code evidence.rs::artifact_exists}。
     *
     * <p>它只给账本记 {@code existed_at_register} 用：登记时在、核账时不见了 = 删掉了。
     * 判"做没做出来"走 {@link #produced}（要求<b>和之前不同</b>）。
     */
    static boolean artifactExists(Path scriptDir, TaskKind kind, String target, ChapterLander lander) {
        if (kind == null) {
            return false;
        }
        return switch (kind) {
            // 原版查 Chapters/<id>.yaml，这里同口径（落盘时 YAML 与库里那一行一起写）
            case WRITE_CHAPTER, REVISE_CHAPTER -> chapterSnapshot(scriptDir, target).exists();
            case CHECK_ASSETS -> fileSnapshot(agentFile(scriptDir, ASSETS_REL_PATH)).exists();
            case OUTLINE, REVISE_OUTLINE -> fileSnapshot(agentFile(scriptDir, ScriptFacts.DESIGN_REL_PATH)).exists();
            case DRAFT_CHAPTER -> {
                String key = chapterKey(target);
                yield !key.isEmpty()
                        && titled(agentFile(scriptDir, ScriptFacts.CHAPTER_DETAILS_REL_PATH), key);
            }
            default -> false;
        };
    }

    /** 物证核对的三档结论（对齐 {@code evidence.rs::Evidence}，{@code Open} 以外的两档会进归档）。 */
    enum Evidence {
        /** 还没做出来，这一项继续欠着。 */
        OPEN,
        /** 这一轮真的做出来了（登记之后被改写过）。 */
        DONE,
        /** 东西本来就在、这一轮没动过：不算做完也不算欠。 */
        EXISTING
    }

    /**
     * 判定"这一轮做出来的"（对齐 {@code evidence.rs::entry_evidence}）：
     * 产出型看产物在不在<b>且登记之后被改过</b>（在但没动给 {@code EXISTING}，不算欠）；
     * 改类看"出现 / 消失"二者之一；删类只看 {@code existed_at_register}。
     *
     * <p>与 {@link #produced} 的分工：{@code produced} 是"要不要强制二跑"的开关（只认 {@code DONE}），
     * 这里是账本的勾，多一档 {@code EXISTING}。
     *
     * @param beforeAt 登记时刻（Unix 纳秒）；{@code null} 的快照不参与"改过没有"的判断
     */
    static Evidence entryEvidence(Path scriptDir, TaskKind kind, String target, long registeredAtNanos,
                                  boolean existedAtRegister, ChapterLander lander) {
        if (kind == null) {
            return Evidence.OPEN;
        }
        Snapshot now = capture(kind, target, scriptDir, null, lander);
        return switch (kind) {
            case WRITE_CHAPTER -> {
                if (!now.exists()) {
                    yield Evidence.OPEN;
                }
                yield changedAfter(now, registeredAtNanos) ? Evidence.DONE : Evidence.EXISTING;
            }
            case REVISE_CHAPTER -> {
                if (changedAfter(now, registeredAtNanos)
                        || (!now.exists() && existedAtRegister)) {
                    yield Evidence.DONE;
                }
                yield Evidence.OPEN;
            }
            case CHECK_ASSETS, OUTLINE, REVISE_OUTLINE, DRAFT_CHAPTER -> {
                if (!now.exists()) {
                    yield Evidence.OPEN;
                }
                yield changedAfter(now, registeredAtNanos) ? Evidence.DONE : Evidence.EXISTING;
            }
            default -> Evidence.OPEN;
        };
    }

    /**
     * 产物在登记之后被改写过吗。
     *
     * <p>原版比的是 Unix 纳秒；这里盘上拿到的是毫秒，所以要按秒截断再比：
     * {@code Files.writeString} 常给整秒 mtime，直接 {@code mtime*1e6 > registered_nanos} 会把
     * 「同一秒内写完的文件」判成没改过（实测必现）。同一秒内登记又写入按"改过"算，宁松不严 ——
     * 漏判会让模型白跑一次二跑，误判只是账本多勾一行。
     */
    private static boolean changedAfter(Snapshot now, long registeredAtNanos) {
        return now.exists()
                && now.lastModifiedMillis() / 1000L >= registeredAtNanos / 1_000_000_000L;
    }

    // ───────────────────────── 取快照 / 判定 ─────────────────────────

    /** 剧本包里的章节文件 {@code Chapters/<id>.yaml}（原版的产物位置与口径）。 */
    private static Path chapterFile(Path scriptDir, String target) {
        String raw = target == null ? "" : target.trim().replace('\\', '/');
        if (raw.isEmpty() || raw.startsWith("/") || raw.endsWith("/") || raw.contains("..")) {
            return null;
        }
        if (raw.endsWith(".yaml") || raw.endsWith(".yml")) {
            raw = raw.substring(0, raw.lastIndexOf('.'));
        }
        if (scriptDir == null || !raw.matches("[0-9A-Za-z_./-]+")) {
            return null;
        }
        // 章节标识保持在 Chapters/ 之内（原版允许子目录，如 Intro/intro）；扩展名统一补 .yaml
        Path file = scriptDir.resolve("Chapters").resolve(raw).normalize();
        return file.getFileName().toString().contains(".")
                ? file
                : file.resolveSibling(file.getFileName() + ".yaml");
    }


    /**
     * 章节产物在库里：{@code exists} = 盘上 {@code Chapters/} 下有这一章的 YAML（原版口径）。
     *
     * <p>不走 {@code lander.writtenChapterIds(scriptKey)} 的"库里所有行"取法：种子里预置的
     * {@code intro} / {@code basic} 等章节盘上并没有文件，按库算会把"还没写"的章判成"已存在"。
     * 章节 YAML 与库里那一行由 {@code ChapterLander.land} 一起维护，所以"盘上有"即"库里也有"。
     */
    private static Snapshot chapterSnapshot(Path scriptDir, String target) {
        return fileSnapshot(chapterFile(scriptDir, target));
    }

    /** 盘上文件的快照：mtime + size + 内容摘要，三者任一变化都算"改过"。 */
    private static Snapshot fileSnapshot(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Snapshot.ABSENT;
        }
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            byte[] bytes = Files.readAllBytes(file);
            return new Snapshot(true, modified.toMillis(), bytes.length, digest(bytes));
        } catch (IOException e) {
            return Snapshot.ABSENT;
        }
    }

    /**
     * 文件在这一项里"被改过且非空"：快照有变化 + 当前内容非空白。
     *
     * <p>比 before 不同是关键：文件本来就在、模型一个字没改，按"存在"判定会放行空跑。
     */
    private static boolean textChanged(Path file, Snapshot before) {
        Snapshot after = fileSnapshot(file);
        if (!after.exists() || after.size() == 0) {
            return false;
        }
        return before == null || !before.exists()
                || before.lastModifiedMillis() != after.lastModifiedMillis()
                || before.size() != after.size()
                || !before.digest().equals(after.digest());
    }

    /** 稿子里有没有点名这一章的小节（{@code ## 第N章} / {@code ## N · …}）：只看标题行。 */
    private static boolean titled(Path file, String key) {
        if (file == null || key.isEmpty()) {
            return false;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }
        for (String line : text.lines().toList()) {
            String head = line.stripLeading();
            if (head.startsWith("#") && chapterKey(head).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 稿子类产物在盘上的位置：{@code <scriptDir>/<relative>}，即
     * {@code <sandbox>/scripts/<key>/.agent/*.md}。
     *
     * <p>所有 {@code .agent/} 路径都由 {@code SkillAgentService.scriptDir} 推导后传进来，
     * 这里<b>不再</b>做「剧本包目录不存在就退回沙箱根」的兜底：{@code .agent/} 永远在剧本包内
     * （对齐原版 {@code stage::DESIGN_REL_PATH = ".agent/design.md"} +
     * {@code script_paths::resolve_script_dir(key)}）。包目录不存在时写 {@code .agent/} 由
     * {@code write_file} 自动建父目录；核对时读不到就是真的没写。
     */
    private static Path agentFile(Path scriptDir, String relative) {
        if (scriptDir == null) {
            return null;
        }
        return scriptDir.resolve(relative).normalize();
    }

    // ───────────────────────── 章节号归一 ─────────────────────────

    /** 两段章节标识是不是同一章：归一后的数字串相同；都没有数字的退回原文比较。 */
    private static boolean sameChapter(String a, String key, String target) {
        if (key.isEmpty()) {
            return a != null && a.trim().equals(target.trim());
        }
        return chapterKey(a).equals(key);
    }

    /**
     * 章节号归一（对齐 {@code evidence.rs::chapter_key}）：取第一个数字串去掉前导零；
     * 不含数字的（{@code Intro/x}、{@code end}）返回空串，不参与比较。
     */
    static String chapterKey(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder run = new StringBuilder();
        for (char ch : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            if (ch >= '0' && ch <= '9') {
                run.append(ch);
            } else if (run.length() > 0) {
                break;
            }
        }
        int start = 0;
        while (start < run.length() && run.charAt(start) == '0') {
            start++;
        }
        return run.substring(start);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
