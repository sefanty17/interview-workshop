package cn.edu.zjut.interview.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptAssembler#extractPlayerSetting} 对齐 {@code prompt.rs:208-233}。
 *
 * <p>用 {@code data/seed/interviewers.json} 的四张真实卡实测：三张带「以下是我的设定」段
 * 会被剥掉，pressure 没有这一段原样返回。数值是<b>裸 {@code startsWith}</b> 算出来的；
 * 若改成先 {@code strip()} 再匹配，行首带空格的卡片也会被误剥，长度就对不上。
 */
class ExtractPlayerSettingTest {

    private static final String SEED = "../data/seed/interviewers.json";

    private static final Map<String, int[]> EXPECTED = Map.of(
            "expert", new int[]{685, 642},
            "friendly", new int[]{649, 558},
            "strict", new int[]{5042, 4957},
            "pressure", new int[]{691, 691});

    @Test
    void 四张种子卡剥离后长度与基准一致() throws Exception {
        Map<String, String> prompts = seedPrompts();
        assertEquals(4, prompts.size(), "种子卡数量应为 4");

        for (Map.Entry<String, int[]> each : EXPECTED.entrySet()) {
            String code = each.getKey();
            String original = prompts.get(code);
            assertNotNull(original, code + " 不在种子文件里");
            assertEquals(each.getValue()[0], original.length(), code + " 原始长度");

            PromptAssembler.ExtractResult result = PromptAssembler.extractPlayerSetting(original);
            assertEquals(each.getValue()[1], result.stripped().length(), code + " 剥离后长度");

            if (original.length() == result.stripped().length()) {
                assertNull(result.segment(), code + " 没有「以下是我的设定」段");
            } else {
                assertNotNull(result.segment(), code + " 应抽出段");
                assertTrue(result.segment().startsWith("以下是我的设定"), code + " 段起点");
                assertFalse(result.stripped().contains("以下是我的设定"), code + " 剥离后不应再有这一段");
            }
        }
    }

    @Test
    void 行首带空格不匹配() {
        String withLeadingSpaces = "人设开头\n  以下是我的设定：我是谁\n人设结尾";

        PromptAssembler.ExtractResult result = PromptAssembler.extractPlayerSetting(withLeadingSpaces);

        assertNull(result.segment(), "行首有空格时裸 startsWith 不应匹配");
        assertEquals(withLeadingSpaces, result.stripped());
    }

    @Test
    void null返回空串且无段() {
        PromptAssembler.ExtractResult result = PromptAssembler.extractPlayerSetting(null);
        assertEquals("", result.stripped());
        assertNull(result.segment());
    }

    private static Map<String, String> seedPrompts() throws Exception {
        File file = new File(SEED);
        assertTrue(file.exists(), "种子文件不存在：" + file.getAbsolutePath());
        String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        JsonNode root = new ObjectMapper().readTree(raw);
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode card : root) {
            out.put(card.path("code").asText(), card.path("persona_prompt").asText());
        }
        return out;
    }
}
