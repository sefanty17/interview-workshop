package cn.edu.zjut.interview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把角色卡目录同步进 {@code interviewer} 表（规格 R1a §4.1）。
 *
 * <p>为什么要有这一步：角色卡的真相源是磁盘上的 {@code game_data/characters/}，
 * 而对话引擎读的是 {@code interviewer} 表。改了 yml 不重启也能通过接口改表，
 * 但重启后必须让表跟磁盘一致，否则「改了没生效」这类问题会非常难查。
 *
 * <p>只覆盖展示类字段，人设与面试流程字段（{@code persona_prompt} / {@code opening} /
 * {@code follow_up} / {@code example_dialogue} / {@code display_role}）一律不动；
 * 表里没有对应 code 时不自动建。全程 try/catch，任何异常只 warn，<b>不影响启动</b>。
 */
@Component
public class CharacterCardSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CharacterCardSyncRunner.class);

    private final CharacterCardService service;

    public CharacterCardSyncRunner(CharacterCardService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            service.syncToInterviewers();
        } catch (Exception e) {
            log.warn("[CharacterCardSyncRunner] 角色卡同步失败（不影响启动）：{}", e.getMessage(), e);
        }
    }
}
