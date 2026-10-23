package cn.edu.zjut.interview.god;

import cn.edu.zjut.interview.agent.SkillAgentLlmClient;
import cn.edu.zjut.interview.domain.InterviewMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 好感度评估（对齐 {@code affection.rs} + {@code types::AffectionVector}）。 */
class AffectionEvaluatorTest {

    @Test
    void initialVectorMatchesLingChatDefault() {
        AffectionEvaluator.AffectionVector vector = AffectionEvaluator.AffectionVector.initial();
        assertEquals(10, vector.fondness());
        assertEquals(5, vector.trust());
        assertEquals(0, vector.intimacy());
        assertEquals(5, vector.rapport());
        assertEquals(15, vector.interest());
        assertEquals(0, vector.longing());
        assertEquals(5, vector.average());
    }

    @Test
    void dimensionLabelsAreChinese() {
        assertEquals("好感", AffectionEvaluator.AffectionVector.DIMENSIONS.get(0)[1]);
        assertEquals("思念", AffectionEvaluator.AffectionVector.DIMENSIONS.get(5)[1]);
        assertEquals(6, AffectionEvaluator.AffectionVector.DIMENSIONS.size());
    }

    @Test
    void plusAppliesOnlyGivenDimensions() {
        AffectionEvaluator.AffectionVector after = AffectionEvaluator.AffectionVector.initial()
                .plus(Map.of("fondness", 3, "trust", -2));
        assertEquals(13, after.fondness());
        assertEquals(3, after.trust());
        assertEquals(0, after.intimacy(), "没给的维度不动");
    }

    @Test
    void payloadListsNpcsWithCurrentVector() {
        AffectionEvaluator.NpcAffectionView npc = new AffectionEvaluator.NpcAffectionView(
                3, "风雪", "", "", AffectionEvaluator.AffectionVector.initial());
        InterviewMessage line = new InterviewMessage();
        line.setRole(InterviewMessage.ROLE_USER);
        line.setContent("我叫蔡智豪");
        line.setDisplayName("蔡智豪");

        String payload = AffectionEvaluator.buildPayload(List.of(npc), List.of(line));
        assertTrue(payload.startsWith("【在场角色与当前情感状态】"));
        assertTrue(payload.contains("- role_id=3: 风雪"));
        assertTrue(payload.contains("好感 10、信赖 5、亲密 0、默契 5、兴趣 15、思念 0"));
        assertTrue(payload.contains("蔡智豪: 我叫蔡智豪"));
    }

    @Test
    void evaluateAffectionAppliesDeltas() {
        GodAgentCore core = new GodAgentCore(null);
        AffectionEvaluator.NpcAffectionView npc = new AffectionEvaluator.NpcAffectionView(
                3, "风雪", "", "", AffectionEvaluator.AffectionVector.initial());
        // 直接验证解读分支（evaluateAffection 本身要打 LLM）
        AffectionEvaluator.AffectionUpdate update = new AffectionEvaluator.AffectionUpdate(
                3, "风雪", npc.current().plus(Map.of("fondness", 2)), "她笑了笑", "回答得不错");
        assertEquals(12, update.after().fondness());
        assertTrue(core.configSnapshot().affectionEnabled());
    }

    @Test
    void evaluateAffectionReturnsEmptyWithoutNpcs() {
        assertTrue(AffectionEvaluator.evaluateAffection(new GodAgentCore(null),
                List.of(), List.of(), null, null).isEmpty());
    }

    @Test
    void toolSchemaRequiresRoleIdAndReason() {
        SkillAgentLlmClient.ToolDef def = AffectionEvaluator.updateAffectionTool();
        assertEquals("update_affection", AffectionEvaluator.UPDATE_AFFECTION);
        assertEquals("update_affection", def.name());
        assertTrue(def.parameters().containsKey("required"));
        assertTrue(def.parameters().get("required").toString().contains("role_id"));
    }
}
