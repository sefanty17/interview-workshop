package cn.edu.zjut.interview.editor;

import cn.edu.zjut.interview.dto.EditorAssetFileView;
import cn.edu.zjut.interview.service.ScriptEditorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 E6a §8：外观页的两个端点（上传背景 / 背景清单）。
 *
 * <p>用 {@code @SpringBootTest} + test profile（内存库），并把
 * {@code app.import.asset-target} 指到测试专用临时目录（与生产同构的
 * {@code <public>/backgrounds/scripts} 结构），真实 {@code frontend/public} 一个字节不碰。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScriptEditorAppearanceTest {

    /** 测试专用 frontend/public 根：静态块里建，随类卸载删除。 */
    private static final Path TEMP_PUBLIC = createTempPublic();

    /** 1x1 无损 webp 的 base64（仅用于验证解码与落盘字节一致）。 */
    private static final String WEBP_BASE64 =
            "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    private static Path createTempPublic() {
        try {
            Path root = Files.createTempDirectory("e6a-appearance-");
            Files.createDirectories(root.resolve("backgrounds").resolve("scripts"));
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("创建测试临时目录失败", e);
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("app.import.asset-target",
                () -> TEMP_PUBLIC.resolve("backgrounds").resolve("scripts").toString());
    }

    @AfterAll
    static void cleanup() throws IOException {
        try (var walk = Files.walk(TEMP_PUBLIC)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Autowired
    private ScriptEditorService service;

    private static String dataUrl() {
        return "data:image/webp;base64," + WEBP_BASE64;
    }

    // ---- ① 合法上传：落盘且返回 url ----

    @Test
    void 合法上传落盘且返回可访问的url() throws IOException {
        EditorAssetFileView uploaded =
                service.uploadEditorBackground(dataUrl(), "e6a_case1_crop.webp");

        assertEquals("e6a_case1_crop.webp", uploaded.name());
        assertEquals("/backgrounds/editor/e6a_case1_crop.webp", uploaded.url());
        assertEquals(Base64.getMimeDecoder().decode(WEBP_BASE64).length, uploaded.size());

        Path written = TEMP_PUBLIC.resolve("backgrounds")
                .resolve("editor").resolve("e6a_case1_crop.webp");
        assertTrue(Files.exists(written), "文件应已落盘：" + written);
        assertArrayEquals(Base64.getMimeDecoder().decode(WEBP_BASE64), Files.readAllBytes(written));
    }

    // ---- ② 非法 name：400 ----

    @Test
    void 非法文件名被拒绝() {
        // 扩展名不对
        EditorBadRequestException e1 = assertThrows(EditorBadRequestException.class,
                () -> service.uploadEditorBackground(dataUrl(), "e6a_case2.png"));
        assertTrue(e1.getMessage().contains("文件名不合法"));
        // 带路径分隔符
        assertThrows(EditorBadRequestException.class,
                () -> service.uploadEditorBackground(dataUrl(), "a/b.webp"));
        // 目录穿越
        assertThrows(EditorBadRequestException.class,
                () -> service.uploadEditorBackground(dataUrl(), "..%2Fx.webp"));
        // dataUrl 前缀不对
        assertThrows(EditorBadRequestException.class,
                () -> service.uploadEditorBackground("data:text/plain;base64,AAAA", "e6a_case2.webp"));
        // dataUrl 缺少 base64 数据段（没有逗号）
        assertThrows(EditorBadRequestException.class,
                () -> service.uploadEditorBackground("data:image/webp;base64", "e6a_case2.webp"));
    }

    // ---- ③ 清单包含刚上传的文件 ----

    @Test
    void 背景清单包含刚上传的文件() {
        service.uploadEditorBackground(dataUrl(), "e6a_case3_crop.webp");

        List<EditorAssetFileView> files = service.listBackgrounds();
        assertTrue(files.stream().anyMatch(f -> f.url().equals("/backgrounds/editor/e6a_case3_crop.webp")),
                "清单应包含刚上传的文件：" + files);
        // 按 url 升序
        for (int i = 1; i < files.size(); i++) {
            assertTrue(files.get(i - 1).url().compareTo(files.get(i).url()) <= 0,
                    "清单应按 url 排序");
        }
    }
}
