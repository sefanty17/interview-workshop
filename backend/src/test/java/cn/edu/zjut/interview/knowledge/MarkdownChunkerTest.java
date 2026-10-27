package cn.edu.zjut.interview.knowledge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 切分规则测试。重点是<b>与 Python 导入脚本的一致性</b>：
 * 网页上传走后端切分、批量导入走脚本，两边切出不同粒度就是 bug。
 */
class MarkdownChunkerTest {

    private static final String FIXTURE = """
            # 测试文档

            开头的引言段，没有二级标题。

            ## 第一节

            正文一。

            ## 第二节

            正文二。

            ### 三级不切

            三级标题下的内容应该归到第二节里。
            """;

    /** 与 import_knowledge.py --selftest 的第一组断言逐条对应。 */
    @Test
    void 按一二三级标题切分且三级并入所属小节() {
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(FIXTURE);

        assertEquals(3, chunks.size(), "应切 3 段");
        assertEquals("测试文档", chunks.get(0).heading(), "引言段的标题取 H1");
        assertEquals("测试文档 > 第一节", chunks.get(1).heading());
        assertEquals("测试文档 > 第二节", chunks.get(2).heading());
        assertTrue(chunks.get(2).text().contains("三级不切"), "### 内容必须并入二级节");
    }

    @Test
    void 超长小节按空行二次切且保留同一面包屑() {
        String paragraph = "这是一段用于测试切分的正文内容。".repeat(8);
        String markdown = "# 长文档\n\n## 超长节\n\n" + (paragraph + "\n\n").repeat(10).strip() + "\n";

        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown);

        assertTrue(chunks.size() > 1, "1298 字的小节必须被切开");
        for (MarkdownChunker.Chunk chunk : chunks) {
            assertEquals("长文档 > 超长节", chunk.heading());
            assertTrue(chunk.text().length() <= MarkdownChunker.MAX_CHUNK_CHARS + 50,
                    "切开后仍超长：" + chunk.text().length());
        }
    }

    @Test
    void 空文档切成零段() {
        assertTrue(MarkdownChunker.chunk("").isEmpty());
        assertTrue(MarkdownChunker.chunk(null).isEmpty());
    }

    /**
     * 拿仓库里的真实语料做回归：这篇 md 用 Python 脚本切出 4 段（见 --dry-run 输出），
     * Java 侧也必须是 4 段、且第一段落在「运行时内存区域划分」。
     */
    @Test
    void 与导入脚本对同一篇语料切出相同段数() throws IOException {
        Path file = Path.of("..", "data", "knowledge", "java", "jvm-memory.md");
        assumeTrue(Files.exists(file), "语料文件不存在则跳过（CI 上可能没带 data 目录）");

        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(
                Files.readString(file, StandardCharsets.UTF_8));

        assertEquals(4, chunks.size(), "python scripts/import_knowledge.py --dry-run 切出 4 段");
        assertTrue(chunks.get(0).heading().contains("运行时内存区域划分"),
                "第一段应是第一个 ## 小节，实际 " + chunks.get(0).heading());
    }
}
