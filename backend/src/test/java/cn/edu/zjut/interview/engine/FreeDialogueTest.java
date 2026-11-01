package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.UserProfile;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.ReplyEvent;
import cn.edu.zjut.interview.engine.payload.ModifyCharacterPayload;
import cn.edu.zjut.interview.importer.ImportResult;
import cn.edu.zjut.interview.importer.ScriptYamlImporter;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.repository.UserProfileRepository;
import cn.edu.zjut.interview.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * 自由对话的收尾与下发验收（原版 {@code free_dialogue_event.rs:150-232}）。
 *
 * <p>原版是进程内 {@code loop} + {@code rx.await}，收尾条件有两条（任一成立即最后一轮）：
 * <ol>
 *   <li>{@code max_rounds > 0 && rounds >= max_rounds}（{@code :155}）</li>
 *   <li>{@code !end_line.is_empty() && user_input.contains(end_line)}（{@code :199}）</li>
 * </ol>
 * 剧本不写 {@code max_rounds} 时原版存 {@code -1}（{@code :69}），第 1 条永不成立，
 * 只能靠第 2 条（玩家打出结束词）收尾——这就是 imported-2 卡在 main2 的原因。
 *
 * <p>剧本用 {@link ScriptYamlImporter} 现场导入到独立内存库，不依赖外部数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:free-dialogue-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "app.import.asset-target=target/test-assets/backgrounds/scripts"
})
@ActiveProfiles("test")
class FreeDialogueTest {

    @TempDir
    Path tempDir;

    @Autowired
    private ScriptYamlImporter importer;
    @Autowired
    private InterviewEngine engine;
    @Autowired
    private ChatService chatService;
    @Autowired
    private InterviewSessionRepository sessionRepository;
    @Autowired
    private UserProfileRepository userProfileRepository;

    @MockBean
    private LlmClient llmClient;

    // ---------- 卡点 2：hint 下发 ----------

    @Test
    void freeDialogueSendsRealHint() throws Exception {
        String code = script("fd-hint-case", """
                - type: free_dialogue
                  character: MAIN
                  hint: 可以在公园和钦灵随便聊哦！
                  end_line: 去别的地方逛逛吧？
                """);

        RecordingSink sink = new RecordingSink();
        engine.run(newSession(code), sink);

        assertEquals(List.of("可以在公园和钦灵随便聊哦！"), sink.hints,
                "free_dialogue 下发的是剧本写的 hint，不是空串");
    }

    @Test
    void freeDialogueWithoutHintFallsBackToOriginalDefault() throws Exception {
        String code = script("fd-no-hint-case", """
                - type: free_dialogue
                  character: MAIN
                  end_line: 结束
                """);

        RecordingSink sink = new RecordingSink();
        engine.run(newSession(code), sink);

        assertEquals(List.of("自由对话..."), sink.hints, "原版缺省提示词（free_dialogue_event.rs:64）");
    }

    // ---------- 卡点 1：收尾分支 ----------

    @Test
    void endLineHitEndsFreeDialogueAndAdvancesChapter() throws Exception {
        String code = script("fd-end-line-case", """
                - type: free_dialogue
                  character: MAIN
                  hint: 可以在公园和钦灵随便聊哦！
                  end_line: 去别的地方逛逛吧？
                """);

        Long sessionId = newSession(code);
        engine.run(sessionId, new RecordingSink());
        assertFreeDialogueOpen(sessionId, 0, "进入时停在 free_dialogue");

        // 没写 max_rounds（maxRounds = -1），只能靠 end_line 收尾
        RecordingSink sink = new RecordingSink();
        engine.submitAndRun(sessionId, "去别的地方逛逛吧？", sink);

        assertTrue(sink.chapters.contains("next"), "命中 end_line 应推进到下一章：" + sink.chapters);
        assertTrue(sink.narrations.contains("到了下一章"), "下一章的 narration 应执行：" + sink.narrations);
        assertTrue(sink.finished, "next 章跑完剧本结束");
        assertNull(freeDialogueState(sessionId), "收尾后 variable_info 里的 freeDialogue 要清掉");
    }

    @Test
    void endLineSubstringMatchAlsoEndsFreeDialogue() throws Exception {
        String code = script("fd-substring-case", """
                - type: free_dialogue
                  character: MAIN
                  end_line: 去别的地方逛逛吧？
                """);

        Long sessionId = newSession(code);
        engine.run(sessionId, new RecordingSink());

        // 原版 :199 是 contains（子串匹配），不是 equals
        RecordingSink sink = new RecordingSink();
        engine.submitAndRun(sessionId, "那我们去别的地方逛逛吧？走起", sink);

        assertTrue(sink.chapters.contains("next"), "子串命中也算收尾：" + sink.chapters);
        assertNull(freeDialogueState(sessionId));
    }

    @Test
    void inputMissingEndLineKeepsFreeDialogueOpen() throws Exception {
        String code = script("fd-miss-case", """
                - type: free_dialogue
                  character: MAIN
                  end_line: 去别的地方逛逛吧？
                """);

        Long sessionId = newSession(code);
        engine.run(sessionId, new RecordingSink());

        RecordingSink sink = new RecordingSink();
        engine.submitAndRun(sessionId, "今天的樱花真好看", sink);

        assertFalse(sink.chapters.contains("next"), "没命中结束词不许推进：" + sink.chapters);
        assertEquals(1, freeDialogueState(sessionId).path("used").asInt(), "用掉 1 轮");
        assertTrue(freeDialogueState(sessionId).path("switch").asBoolean(), "仍处于自由对话");
    }

    @Test
    void maxRoundsReachedEndsFreeDialogueWithoutEndLine() throws Exception {
        String code = script("fd-max-rounds-case", """
                - type: free_dialogue
                  character: MAIN
                  max_rounds: 2
                """);

        Long sessionId = newSession(code);
        engine.run(sessionId, new RecordingSink());

        engine.submitAndRun(sessionId, "第一轮", new RecordingSink());
        assertEquals(1, freeDialogueState(sessionId).path("used").asInt());
        assertTrue(freeDialogueState(sessionId).path("switch").asBoolean(), "第 1 轮不该收尾");

        RecordingSink sink = new RecordingSink();
        engine.submitAndRun(sessionId, "第二轮", sink);

        assertTrue(sink.chapters.contains("next"), "max_rounds=2 用尽即收尾：" + sink.chapters);
        assertNull(freeDialogueState(sessionId));
    }

    @Test
    void maxRoundsMinusOneNeverEndsOnItsOwn() throws Exception {
        String code = script("fd-infinite-case", """
                - type: free_dialogue
                  character: MAIN
                  end_line: 结束对话
                """);

        Long sessionId = newSession(code);
        engine.run(sessionId, new RecordingSink());
        assertEquals(-1, freeDialogueState(sessionId).path("maxRounds").asInt(),
                "剧本没写 max_rounds 时原版存 -1（free_dialogue_event.rs:69）");

        for (int i = 0; i < 5; i++) {
            engine.submitAndRun(sessionId, "继续聊 " + i, new RecordingSink());
        }
        assertEquals(5, freeDialogueState(sessionId).path("used").asInt(),
                "maxRounds=-1 时轮数再大也不自动收尾");
        assertTrue(freeDialogueState(sessionId).path("switch").asBoolean());
    }

    // ---------- 卡点 3：{user} 替换 ----------

    @Test
    void narrationReplacesUserPlaceholderWithProfileName() throws Exception {
        String code = script("fd-user-case", """
                - type: narration
                  text: 于是钦灵和{user}整理好了小包，出门前往公园！
                """);

        RecordingSink sink = new RecordingSink();
        engine.run(newSession(code), sink);

        String expected = userProfileRepository.findFirstByOrderByIdAsc()
                .map(UserProfile::getNickname).orElseThrow(() -> new AssertionError("测试库里应有 user_profile"));
        assertTrue(sink.narrations.contains("于是钦灵和" + expected + "整理好了小包，出门前往公园！"),
                "{user} 应换成 user_profile.nickname，实际：" + sink.narrations);
        assertFalse(sink.narrations.stream().anyMatch(text -> text.contains("{user}")),
                "不该残留 {user}：" + sink.narrations);
    }

    @Test
    void userPlaceholderFallsBackToNiWhenProfileHasNoName() {
        assertEquals("于是钦灵和你出门", ScriptFunctions.replacePlaceholders("于是钦灵和{user}出门", "候选人", null));
        assertEquals("于是钦灵和你出门", ScriptFunctions.replacePlaceholders("于是钦灵和{user}出门", "候选人", "  "));
        assertEquals("于是钦灵和候选人出门", ScriptFunctions.replacePlaceholders("于是钦灵和{user}出门", "候选人", "候选人"));
        assertEquals("你好，候选人", ScriptFunctions.replacePlaceholders("你好，%player%", "候选人", "候选人"),
                "%player% 仍按原版取 player 名");
    }

    // ==================== 夹具 ====================

    /**
     * 造一个两章剧本：{@code main} 放传入的事件（外加 chapter_end → next），
     * {@code next} 只放一条 narration，用来判断「有没有真的推进过去」。
     */
    private String script(String folder, String events) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(folder));
        write(dir.resolve("story_config.yaml"), """
                script_name: 自由对话用例
                intro_chapter: main
                """);
        Path chapters = Files.createDirectories(dir.resolve("Chapters"));
        write(chapters.resolve("main.yaml"), """
                name: 主章
                events:
                """ + indent(events) + """
                  - type: chapter_end
                    end_type: linear
                    next_chapter: "next"
                """);
        write(chapters.resolve("next.yaml"), """
                name: 下一章
                events:
                  - type: narration
                    text: 到了下一章
                """);
        ImportResult result = importer.importDirectory(dir);
        assertEquals(0, result.report().summary().red(), "用例剧本不该有红项：" + result.report().red());
        return result.scriptCode();
    }

    private Long newSession(String scriptCode) {
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("【微笑】好的。");
            return null;
        }).when(llmClient).stream(anyList(), any());
        return chatService.createSession(new CreateSessionRequest("strict", "java", scriptCode))
                .sessionId();
    }

    private JsonNode freeDialogueState(Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        JsonNode vars = new ObjectMapper().createObjectNode();
        try {
            vars = new ObjectMapper().readTree(session.getVariableInfo() == null
                    ? "{}" : session.getVariableInfo());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        JsonNode free = vars.get("freeDialogue");
        return free == null || free.isNull() ? null : free;
    }

    private void assertFreeDialogueOpen(Long sessionId, int used, String message) {
        JsonNode free = freeDialogueState(sessionId);
        assertTrue(free != null && free.path("switch").asBoolean(), message);
        assertEquals(used, free.path("used").asInt());
    }

    private static void write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 调用方传进来的事件片段自带 2 空格缩进，这里统一再压进 {@code events:} 下面。 */
    private static String indent(String events) {
        StringBuilder out = new StringBuilder();
        for (String line : events.split("\n", -1)) {
            out.append(line.isBlank() ? line : "  ").append(line).append('\n');
        }
        return out.toString();
    }

    /** 只收集断言需要的三类事件，不碰网络。 */
    private static final class RecordingSink implements EventSink {

        private final List<String> hints = new ArrayList<>();
        private final List<String> narrations = new ArrayList<>();
        private final List<String> chapters = new ArrayList<>();
        private boolean finished;

        @Override
        public void reply(ReplyEvent event) {
        }

        @Override
        public void narration(String text) {
            narrations.add(text);
        }

        @Override
        public void input(String hint) {
            hints.add(hint);
        }

        @Override
        public void character(ModifyCharacterPayload payload) {
        }

        @Override
        public void chapter(String code, String name) {
            chapters.add(code);
        }

        @Override
        public void progress(String chapter, int eventIndex, int lineCount, JsonNode vars) {
        }

        @Override
        public void finished() {
            finished = true;
        }
    }
}
