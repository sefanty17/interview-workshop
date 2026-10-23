package cn.edu.zjut.interview.domain;

import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import cn.edu.zjut.interview.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体与 DDL 的列名/类型对齐校验。
 *
 * <p>count() 只用到表名，证明不了列映射；这里真实发一次带全部列的 SELECT，
 * 列名写错（比如 card_title 写成 cardTitle）会直接抛 SQL 异常。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional(readOnly = true)
class SchemaMappingTest {

    @Autowired
    private InterviewerRepository interviewerRepository;
    @Autowired
    private UserProfileRepository userProfileRepository;
    @Autowired
    private InterviewScriptRepository interviewScriptRepository;
    @Autowired
    private InterviewChapterRepository interviewChapterRepository;
    @Autowired
    private InterviewSessionRepository interviewSessionRepository;
    @Autowired
    private InterviewMessageRepository interviewMessageRepository;

    @Test
    void interviewerColumnsMap() {
        Interviewer strict = interviewerRepository.findByCode("strict").orElseThrow();
        assertNotNull(strict.getId());
        assertNotNull(strict.getPersonaPrompt());
        assertNotNull(strict.getDisplayRole());
        assertTrue(strict.getEnabled());
        assertNotNull(strict.getCreatedAt());
        assertEquals(4, interviewerRepository.findByEnabledTrueOrderBySortOrderAsc().size());
    }

    @Test
    void userProfileColumnsMap() {
        UserProfile profile = userProfileRepository.findFirstByOrderByIdAsc().orElseThrow();
        assertNotNull(profile.getNickname());
        assertNotNull(profile.getResumeSummary());
    }

    @Test
    void scriptAndChapterColumnsMap() {
        InterviewScript script = interviewScriptRepository.findByCode("java-strict").orElseThrow();
        assertNotNull(script.getScriptName());
        assertNotNull(script.getInterviewerId());
        assertEquals("校招", script.getLevel());

        List<InterviewChapter> chapters = interviewChapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId());
        assertEquals(6, chapters.size());
        assertEquals("intro", chapters.get(0).getCode());
        assertTrue(chapters.get(0).getEvents().startsWith("["));

        InterviewChapter intro = interviewChapterRepository
                .findByScriptIdAndCode(script.getId(), "intro").orElseThrow();
        assertEquals("破冰与自我介绍", intro.getName());
    }

    @Test
    void sessionAndMessageColumnsMap() {
        // 种子数据里没有场次和台词，空结果也能验证列名（Hibernate 会先把所有列写进 SELECT）
        assertTrue(interviewSessionRepository.findAll().isEmpty());
        assertFalse(interviewMessageRepository.findBySessionIdOrderBySeqAsc(1L).iterator().hasNext());
    }
}
