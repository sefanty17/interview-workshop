package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.PlayerIdentity;
import cn.edu.zjut.interview.prompt.PlayerIdentityPrompt;
import cn.edu.zjut.interview.repository.InterviewerRelationRepository;
import cn.edu.zjut.interview.repository.PlayerIdentityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双向关系：{@code interviewer_relation} 的整份覆盖往返 + 关系解析的说话者优先。
 *
 * <p>对齐 {@code role_relations::save} / {@code resolve_relation}：
 * 两侧都写了关系时说话者侧（{@code SPEAKER}）胜出；
 * 只有对方侧（{@code COUNTERPARTY}）时，「你和我的关系」那一行不输出。
 */
@SpringBootTest
@ActiveProfiles("test")
class IdentityRelationTest {

    private static final String CODE = "__relation_test__";

    @Autowired
    private InterviewerRelationService relationService;

    @Autowired
    private PlayerIdentityService identityService;

    @Autowired
    private InterviewerRelationRepository relationRepository;

    @Autowired
    private PlayerIdentityRepository identityRepository;

    private final PlayerIdentityPrompt prompt = new PlayerIdentityPrompt(new ObjectMapper());

    @BeforeEach
    void clean() {
        relationRepository.deleteAll(relationRepository.findByInterviewerCode(CODE));
    }

    @Test
    void 保存后读回三条键值一致() {
        Map<String, String> relations = new LinkedHashMap<>();
        relations.put("me:alice", "青梅竹马");
        relations.put("me:bob", "前同事");
        relations.put("ai:strict", "宿敌");

        relationService.save(CODE, relations);
        Map<String, String> loaded = relationService.load(CODE);

        assertEquals(3, loaded.size());
        assertEquals("青梅竹马", loaded.get("me:alice"));
        assertEquals("前同事", loaded.get("me:bob"));
        assertEquals("宿敌", loaded.get("ai:strict"));
    }

    @Test
    void 再次保存是整份覆盖() {
        relationService.save(CODE, Map.of("me:alice", "青梅竹马", "me:bob", "前同事"));
        relationService.save(CODE, Map.of("me:alice", "点头之交"));

        Map<String, String> loaded = relationService.load(CODE);
        assertEquals(1, loaded.size(), "整份覆盖后只该剩一条");
        assertEquals("点头之交", loaded.get("me:alice"));
    }

    @Test
    void 两侧都有关系时说话者侧胜出() {
        // 说话者（角色 strict）对 alice 写的是「宿敌」
        relationService.save("strict", Map.of("me:" + currentId(), "宿敌"));

        Map<String, String> speakerRelations = relationService.loadEffective("strict", null);
        var speaker = new PlayerIdentityPrompt.AiEndpoint("strict");
        var target = new PlayerIdentityPrompt.MeEndpoint(currentId());
        // 对方（身份卡）侧也写了 ai:strict
        Map<String, String> targetRelations = Map.of("ai:strict", "青梅竹马");

        var view = prompt.resolveRelation(speaker, target, speakerRelations, targetRelations, null);

        assertTrue(view.isPresent());
        assertEquals(PlayerIdentityPrompt.RelationSource.SPEAKER, view.get().source());
        assertEquals("宿敌", view.get().text());
    }

    @Test
    void 只有对方侧时玩家块不输出关系行() {
        var speaker = new PlayerIdentityPrompt.AiEndpoint("strict");
        var target = new PlayerIdentityPrompt.MeEndpoint(currentId());
        // 说话者侧空表，只有身份卡上写了「ai:strict」
        Map<String, String> targetRelations = Map.of("ai:strict", "青梅竹马");

        var view = prompt.resolveRelation(speaker, target, Map.of(), targetRelations, null);
        assertTrue(view.isPresent());
        assertEquals(PlayerIdentityPrompt.RelationSource.COUNTERPARTY, view.get().source());

        String block = prompt.buildPlayerBlock("小明", null, view.get());
        assertTrue(block.contains("名字：小明"));
        assertFalse(block.contains("你和我的关系"), "对方侧的关系不能当成说话者的态度");
        assertFalse(block.contains("青梅竹马"));
    }

    /** 当前身份的 id；没有身份时临时存一张（测试库每次都是新内存库）。 */
    private String currentId() {
        return identityService.current().map(PlayerIdentity::getIdentityId).orElseGet(() -> {
            PlayerIdentity card = new PlayerIdentity();
            card.setName("小明");
            card.setIsCurrent(Boolean.TRUE);
            return identityService.save(card).getIdentityId();
        });
    }
}
