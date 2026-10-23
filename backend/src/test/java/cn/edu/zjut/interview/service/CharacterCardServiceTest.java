package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.dto.character.CharacterCard;
import cn.edu.zjut.interview.dto.character.CharacterCardSummary;
import cn.edu.zjut.interview.editor.CharacterNotFoundException;
import cn.edu.zjut.interview.editor.EditorBadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 R1a §6 的 9 条断言（{@code @SpringBootTest} + test profile，与
 * {@code ScriptEditorServiceBackupTest} 同一风格，不碰开发库）。
 *
 * <p>用例里新建的 {@code tmp-char} 由 {@code @AfterEach} 兜底删除，
 * 跑完 {@code game_data/characters/} 必须只剩 4 张原卡。
 */
@SpringBootTest
@ActiveProfiles("test")
class CharacterCardServiceTest {

    /** 用例专用的临时角色目录（不含 . / \，能通过目录名校验）。 */
    private static final String TMP_FOLDER = "tmp-char";

    @Autowired
    private CharacterCardService service;

    @AfterEach
    void cleanUp() {
        try {
            service.deleteCharacter(TMP_FOLDER);
        } catch (Exception ignored) {
            // 目录本来就不存在，或者上一条用例已经删掉了
        }
    }

    /** 1. 列出 4 张原卡，folder 集合与规格一致。 */
    @Test
    void 列出四张原卡() {
        List<CharacterCardSummary> cards = service.listCharacters();
        assertEquals(4, cards.size(), "应该有 4 张原版角色卡");
        assertEquals(Set.of("风雪", "诺一钦灵", "松子", "DeepSeek"),
                cards.stream().map(CharacterCardSummary::folder).collect(Collectors.toSet()));
    }

    /** 2. 风雪卡的字段值。 */
    @Test
    void 风雪卡字段正确() {
        CharacterCard card = service.getCharacter("风雪");
        assertEquals("风雪", card.aiName());
        assertEquals("LingChat Studio", card.aiSubtitle());
        assertEquals("钦灵", card.userName());
        assertEquals(1.25, card.scale());
        assertEquals(65.0, card.offsetY());
    }

    /** 3. rawYaml 含 ai_name 且中文不转义。 */
    @Test
    void rawYaml保留中文不转义() {
        String raw = service.getCharacter("风雪").rawYaml();
        assertNotNull(raw);
        assertTrue(raw.contains("ai_name"), "rawYaml 应该带原版 snake_case 键");
        assertTrue(raw.contains("风雪"), "rawYaml 里的中文不能被转义");
        assertFalse(raw.contains("\\u"), "rawYaml 不该出现 \\u 转义");
    }

    /** 4. 不存在的目录 → CharacterNotFoundException（controller 转 404）。 */
    @Test
    void 不存在的目录抛角色卡不存在() {
        assertThrows(CharacterNotFoundException.class, () -> service.getCharacter("不存在的目录"));
    }

    /** 5. 新建后目录与文件都在；重复新建 → 异常。 */
    @Test
    void 新建与重复新建() {
        service.createCharacter(TMP_FOLDER, initialFields());
        CharacterCard created = service.getCharacter(TMP_FOLDER);
        assertEquals("临时角色", created.aiName(), "新建时给的初始字段应该落盘");

        assertThrows(EditorBadRequestException.class,
                () -> service.createCharacter(TMP_FOLDER, initialFields()), "重复新建应该被拒");
    }

    /** 6. 保存后重新读能拿到新值。 */
    @Test
    void 保存后能读到新值() {
        service.createCharacter(TMP_FOLDER, initialFields());

        Map<String, Object> updated = new LinkedHashMap<>();
        updated.put("ai_name", "改名后的临时角色");
        updated.put("scale", 2.0);
        service.saveCharacter(TMP_FOLDER, updated);

        CharacterCard reloaded = service.getCharacter(TMP_FOLDER);
        assertEquals("改名后的临时角色", reloaded.aiName());
        assertEquals(2.0, reloaded.scale());
    }

    /** 7. 路径穿越：{@code ../..} → 400（不是 404，也不是真去读上级目录）。 */
    @Test
    void 路径穿越报400() {
        assertThrows(EditorBadRequestException.class, () -> service.getCharacter("../.."));
        assertThrows(EditorBadRequestException.class, () -> service.deleteCharacter(".."));
    }

    /** 8. 删除后目录不存在；再删 → 404。 */
    @Test
    void 删除后再删报404() {
        service.createCharacter(TMP_FOLDER, initialFields());
        service.deleteCharacter(TMP_FOLDER);
        assertThrows(CharacterNotFoundException.class, () -> service.getCharacter(TMP_FOLDER));
        assertThrows(CharacterNotFoundException.class, () -> service.deleteCharacter(TMP_FOLDER));
    }

    /** 9. 收尾：任何时刻列表里都只有 4 张原卡（用例没留脏数据）。 */
    @Test
    void 跑完只剩四张原卡() {
        assertEquals(4, service.listCharacters().size());
        assertThrows(CharacterNotFoundException.class, () -> service.getCharacter(TMP_FOLDER));
    }

    private static Map<String, Object> initialFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ai_name", "临时角色");
        fields.put("ai_subtitle", "单元测试");
        fields.put("user_name", "测试员");
        fields.put("title", "临时卡");
        fields.put("avatar_mode", "image");
        fields.put("scale", 1.0);
        fields.put("offset_x", 0.0);
        fields.put("offset_y", 0.0);
        fields.put("bubble_top", 5);
        fields.put("bubble_left", 25);
        return fields;
    }
}
