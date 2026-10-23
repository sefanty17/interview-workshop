package cn.edu.zjut.interview.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * 暴露给 LLM 的文件工具，除非显式开启 {@code allowAnyPath}，一律受沙箱约束。
 * 1:1 迁移自 {@code crates/ling-chat-main/src/ai_service/skill_agent/file_tools.rs}
 * （grep/glob/search 三件原版有、E7a2 工具清单用不到，未迁移）。
 *
 * <p>所有方法成功时返回给 LLM 看的文本，失败时抛 {@link IllegalArgumentException}
 * （消息即给 LLM 看的错误文案，由 {@link SkillAgentTools} 统一捕获变成
 * {@code ok:false}，不会漏到 HTTP 层变成 500）。
 */
public class SkillAgentFileTools {

    /** 单次读取上限 200KB（对齐 {@code file_tools.rs::MAX_READ_BYTES}），超出截断。 */
    private static final int MAX_READ_BYTES = 200 * 1024;
    /** 目录列表上限 500 项（对齐 {@code file_tools.rs::MAX_LIST_ENTRIES}）。 */
    private static final int MAX_LIST_ENTRIES = 500;

    /** 文件沙箱根目录。 */
    private final Path sandboxDir;
    /** true 时允许访问沙箱之外的任意路径（对齐 {@code allow_any_path}）。 */
    private final boolean allowAnyPath;

    public SkillAgentFileTools(Path sandboxDir, boolean allowAnyPath) {
        this.sandboxDir = sandboxDir.toAbsolutePath().normalize();
        this.allowAnyPath = allowAnyPath;
    }

    // ───────────────────────── 路径（sanitize / canonicalize_allow_missing） ─────────────────────────

    /**
     * 解析路径：相对路径拼到沙箱根下；对最深一层存在的祖先做真实路径归一（含大小写、符号链接），
     * 拼回缺失的后缀，再做词法归一。越界（不以沙箱根开头）直接拒绝，措辞照搬原版。
     */
    Path sanitize(String path) {
        String trimmed = path == null ? "" : path.trim();
        Path requested = Path.of(trimmed);
        Path joined = requested.isAbsolute() ? requested : sandboxDir.resolve(requested);
        Path target = canonicalizeAllowMissing(joined).normalize();
        if (allowAnyPath) {
            return target;
        }
        Path root = canonicalizeAllowMissing(sandboxDir).normalize();
        if (target.startsWith(root)) {
            return target;
        }
        throw new IllegalArgumentException(
                "拒绝访问文件沙箱之外的路径: " + trimmed + "（可在“助手设置”中开启允许任意路径）");
    }

    /**
     * 对应原版 {@code canonicalize_allow_missing}：目标可能尚不存在（write_file 新建文件），
     * 从下往上找到最深一层存在的祖先做 {@link Path#toRealPath}，再把缺失的后缀拼回去。
     */
    private static Path canonicalizeAllowMissing(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Deque<String> suffix = new ArrayDeque<>();
        Path cursor = absolute;
        while (!Files.exists(cursor)) {
            Path name = cursor.getFileName();
            if (name == null) {
                return absolute; // 已经到盘根还不存在，只能按字面量返回
            }
            suffix.addFirst(name.toString());
            Path parent = cursor.getParent();
            if (parent == null) {
                return absolute;
            }
            cursor = parent;
        }
        try {
            Path base = cursor.toRealPath();
            for (String part : suffix) {
                base = base.resolve(part);
            }
            return base.normalize();
        } catch (IOException e) {
            return absolute;
        }
    }

    // ───────────────────────── list_files ─────────────────────────

    /** 列出目录项（原版 {@code list_files} 的排版：📂 目录头 + 📄/📂 逐项 + 截断提示）。 */
    public String listFiles(String path) {
        Path dir = sanitize(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("目录不存在: " + dir);
        }
        List<Path> entries;
        try (var stream = Files.list(dir)) {
            entries = stream.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("读取目录失败: " + e.getMessage());
        }
        StringBuilder lines = new StringBuilder("📂 ").append(dir).append('\n');
        int shown = 0;
        for (Path entry : entries) {
            if (shown >= MAX_LIST_ENTRIES) {
                lines.append("...[条目过多，仅显示前 ").append(MAX_LIST_ENTRIES).append(" 项]...\n");
                break;
            }
            lines.append(Files.isDirectory(entry) ? "📂 " : "📄 ")
                    .append(entry.getFileName()).append('\n');
            shown++;
        }
        return lines.toString();
    }

    // ───────────────────────── read_file ─────────────────────────

    /** 读取文本文件（原版 {@code read_file} 的排版），超 200KB 截断并附提示。 */
    public String readFile(String path) {
        Path file = sanitize(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文件失败: " + e.getMessage());
        }
        boolean truncated = bytes.length > MAX_READ_BYTES;
        if (truncated) {
            byte[] cut = new byte[MAX_READ_BYTES];
            System.arraycopy(bytes, 0, cut, 0, MAX_READ_BYTES);
            bytes = cut;
        }
        StringBuilder out = new StringBuilder("===== ").append(file).append(" =====\n")
                .append(new String(bytes, StandardCharsets.UTF_8));
        if (truncated) {
            out.append("\n...[文件过大，已截断]...");
        }
        return out.toString();
    }

    // ───────────────────────── write_file ─────────────────────────

    /**
     * 写入完整文件（父目录自动创建）；仅显式 {@code append=true} 时追加到已有文件末尾
     * （原版 {@code write_text}：追加失败没文件时走覆盖写）。
     */
    public String writeFile(String path, String content, boolean append) {
        Path file = sanitize(path);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (append && Files.exists(file)) {
                Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } else {
                atomicWrite(file, content);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("写入文件失败: " + e.getMessage());
        }
        return (append ? "已追加到 " : "已写入 ") + file
                + "（" + content.length() + " 字节）";
    }

    // ───────────────────────── edit_file ─────────────────────────

    /**
     * 精确替换文本；除非 {@code replaceAll=true}，{@code oldString} 必须唯一命中，
     * 否则不改任何东西（错误文案照搬原版）。
     */
    public String editFile(String path, String oldString, String newString, boolean replaceAll) {
        Path file = sanitize(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("old_string 不能为空");
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文件失败: " + e.getMessage());
        }
        int count = countOccurrences(content, oldString);
        if (count == 0) {
            throw new IllegalArgumentException("未找到要替换的文本（old_string 无匹配），请先用 read_file 确认文件内容");
        }
        if (count > 1 && !replaceAll) {
            throw new IllegalArgumentException("old_string 有 " + count
                    + " 处匹配，不唯一；请提供更长的上下文，或确认后设置 replace_all=true");
        }
        String replaced = replaceAll
                ? content.replace(oldString, newString)
                : content.replaceFirst(java.util.regex.Pattern.quote(oldString),
                        java.util.regex.Matcher.quoteReplacement(newString));
        try {
            atomicWrite(file, replaced);
        } catch (IOException e) {
            throw new IllegalArgumentException("写入文件失败: " + e.getMessage());
        }
        return "已改 `" + file + "`（替换 " + (replaceAll ? count : 1) + " 处，未动其余内容）";
    }

    // ───────────────────────── delete_file ─────────────────────────

    /** 删除一个文件（整份）。只能删文件，不能删目录（错误文案照搬原版）。 */
    public String deleteFile(String path) {
        Path file = sanitize(path);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("delete_file 只能删除文件，不能删除目录: " + file);
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("删除文件失败: " + e.getMessage());
        }
        return "已删除 " + file;
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    /**
     * 原版 {@code atomic_write}：先写目标旁的临时文件，完整落盘后再原子替换目标文件。
     */
    private static void atomicWrite(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, ".agent-write-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** 子串出现次数（原版 {@code content.matches(old_string).count()}）。 */
    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
