package cn.edu.zjut.interview.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScriptModes} 的解析规则（1:1 对齐 {@code utils/script_modes.rs}）。
 *
 * <p>判据只有一种：认得出的写法映射到 {@code ONLY_EXISTING} / {@code ALLOW_MISSING}，
 * 认不出就当没声明（走默认的最宽）。
 */
class ScriptModesTest {

    @Test
    void onlyExistingIsTheStrictOneAndOthersAreLenient() {
        assertEquals(ScriptModes.Mode.ONLY_EXISTING, ScriptModes.parse("- 素材模式：只用已有").asset);
        assertEquals(ScriptModes.Mode.ONLY_EXISTING, ScriptModes.parse("- 素材模式：仅用已有").asset);
        // 默认（什么都没写）按最宽走
        assertEquals(ScriptModes.Mode.ALLOW_MISSING, ScriptModes.parse("").asset);
        assertEquals(ScriptModes.Mode.ALLOW_MISSING, ScriptModes.parse(null).cast);
        // 认不出的值不算声明
        assertEquals(ScriptModes.Mode.ALLOW_MISSING, ScriptModes.parse("- 素材模式：随便").asset);
        assertFalse(ScriptModes.declaresAsset("- 素材模式：随便"), "认不出的值不按声明算");
    }

    @Test
    void reserveFirstMeansAllowMissing() {
        assertEquals(ScriptModes.Mode.ALLOW_MISSING, ScriptModes.parse("- 素材模式：先预留").asset);
        assertEquals(ScriptModes.Mode.ALLOW_MISSING, ScriptModes.parse("- 角色卡：之后补（素材库还没有）").cast);
        assertEquals(ScriptModes.Mode.ALLOW_MISSING, ScriptModes.parse("- 素材来源: 允许缺失").asset);
    }

    @Test
    void tableRowIsRecognised() {
        ScriptModes modes = ScriptModes.parse("| 素材模式 | 只用已有 | 写不存在的会报错 |");
        assertEquals(ScriptModes.Mode.ONLY_EXISTING, modes.asset, "表格行取第一列做键、第二列做值");
        assertTrue(ScriptModes.declaresAsset("| 素材模式 | 只用已有 |"));
        // 表格行 + 加粗键名
        assertEquals(ScriptModes.Mode.ONLY_EXISTING,
                ScriptModes.parse("| **素材模式** | `只用已有` |").asset);
    }

    @Test
    void conflictingStatementsReportConflictAndTakeTheLast() {
        String text = "- 素材模式：只用已有\n- 素材来源：允许缺失\n";
        String note = ScriptModes.conflictNote(text);
        assertEquals("卡片里「素材模式」有两处互相矛盾的说法（OnlyExisting 与 AllowMissing），我按最后一条走", note);
        assertEquals(ScriptModes.Mode.ALLOW_MISSING, ScriptModes.parse(text).asset, "判定取最后一条");

        // 同一类里说法一致 → 不报矛盾
        assertNull(ScriptModes.conflictNote("- 素材模式：只用已有\n- 素材来源：仅用已有\n"));
        // 不同类各说各的 → 不报矛盾
        assertNull(ScriptModes.conflictNote("- 素材模式：只用已有\n- 角色卡模式：先预留\n"));
        // 角色卡互相矛盾
        assertEquals("卡片里「角色卡模式」有两处互相矛盾的说法（AllowMissing 与 OnlyExisting），我按最后一条走",
                ScriptModes.conflictNote("- 人物卡：先预留\n- 角色卡模式：只用已有\n"));
    }

    @Test
    void undeclaredVersusDeclared() {
        assertFalse(ScriptModes.declaresAsset(""), "没声明过");
        assertFalse(ScriptModes.declaresAsset("# 素材模式：只用已有"), "注释/标题行不算声明");
        assertFalse(ScriptModes.declaresCast("- 素材模式：只用已有"), "素材的声明不算角色卡声明");
        assertTrue(ScriptModes.declaresCast("- 人物卡模式：允许缺失"));
        assertTrue(ScriptModes.declaresAsset("* `素材`: 零素材（先跑起来）"), "反引号 + 括号解释都认");
    }

    @Test
    void modeSuffixIsStrippedFromKey() {
        // 键写「素材模式」与「素材」等价（field() 去掉「模式」后缀）
        assertEquals("素材", ScriptModes.field("- 素材模式：只用已有").key());
        assertEquals("素材", ScriptModes.field("- 素材：只用已有").key());
        assertEquals("只用已有", ScriptModes.field("- 素材模式：只用已有").value());
        // 值取到第一个标点为止
        assertEquals("只用已有", ScriptModes.field("- 素材模式：只用已有，因为素材没做").value());
        assertEquals("允许缺失", ScriptModes.field("| 素材模式 | 允许缺失 | 备注 |").value());
        assertNull(ScriptModes.field("没有冒号的一行"));
        assertNull(ScriptModes.field("[-] 只有列表符号"));
    }

    @Test
    void describeAndSwitchHintWording() {
        assertEquals("素材模式是「只用已有」：引用磁盘上没有的就是错，当场改",
                ScriptModes.describeOf(ScriptModes.Mode.ONLY_EXISTING, "素材"));
        assertEquals("角色卡模式是「允许缺失」：缺的只提醒、不判错，之后补上即可",
                ScriptModes.describeOf(ScriptModes.Mode.ALLOW_MISSING, "角色卡"));
        assertEquals("素材模式是「允许缺失」：磁盘上还没有的只提醒、不判错，之后补上即可。"
                        + "若想改成「只用已有」，说一句就行；改成后已落盘章节里缺的会变成必须修的错。\n",
                ScriptModes.switchHint(ScriptModes.Mode.ALLOW_MISSING, "素材"));
    }

    @Test
    void usesCharacterCardsByThreeSignals(@TempDir Path dir) throws Exception {
        // ① 卡片声明过
        assertTrue(ScriptModes.usesCharacterCards(null, dir, "- 角色卡模式：先预留"));
        // ② key 以 character/ 开头
        assertTrue(ScriptModes.usesCharacterCards("character/阿绫/雨夜", dir, ""));
        // ③ characters/ 目录非空
        assertFalse(ScriptModes.usesCharacterCards("demo", dir, ""));
        Files.createDirectories(dir.resolve("characters"));
        assertFalse(ScriptModes.usesCharacterCards("demo", dir, ""), "空目录不算");
        Files.writeString(dir.resolve("characters").resolve("a.json"), "{}", StandardCharsets.UTF_8);
        assertTrue(ScriptModes.usesCharacterCards("demo", dir, ""));
    }

    @Test
    void readFromScriptDirFallsBackToDefaults(@TempDir Path dir) throws Exception {
        assertEquals(ScriptModes.Mode.ALLOW_MISSING, ScriptModes.read(dir).asset, "没有约束卡 → 默认");
        assertEquals(ScriptModes.Mode.ALLOW_MISSING, ScriptModes.read(null).asset);
        Files.createDirectories(dir.resolve(".agent"));
        Files.writeString(dir.resolve(ScriptModes.CONSTRAINTS_REL_PATH),
                "- 素材模式：只用已有\r\n- 角色卡模式：只用已有\r\n", StandardCharsets.UTF_8);
        assertEquals(ScriptModes.Mode.ONLY_EXISTING, ScriptModes.read(dir).asset, "CRLF 也要读得出来");
        assertEquals(ScriptModes.Mode.ONLY_EXISTING, ScriptModes.read(dir).cast);
    }
}
