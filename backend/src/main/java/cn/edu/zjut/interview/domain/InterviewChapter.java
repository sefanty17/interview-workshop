package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 流程阶段，对应 DDL {@code interview_chapter}（对齐 LingChat 的 Chapters/{chapter}/{file}.yaml）。
 *
 * <p>events 是 CLOB 里存 JSON 数组，Java 侧保持 String，用到时再反序列化
 * （元素类型：ai_dialogue / input / narration / free_dialogue / chapter_end /
 * modify_character / background / music / sound）。
 */
@Entity
@Table(
        name = "interview_chapter",
        uniqueConstraints = @UniqueConstraint(name = "uq_chap_code", columnNames = {"script_id", "code"})
)
public class InterviewChapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(name = "code", length = 64, nullable = false)
    private String code;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "order_num")
    private Integer orderNum = 0;

    /** JSON 数组，原样存放。 */
    @Column(name = "events", columnDefinition = "CLOB", nullable = false)
    private String events;

    /** 上一次写入前的 events，等价于原版章节 YAML 覆盖前留下的 `.bak`；只保留最近一份。 */
    @Column(name = "events_backup", columnDefinition = "CLOB")
    private String eventsBackup;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getScriptId() {
        return scriptId;
    }

    public void setScriptId(Long scriptId) {
        this.scriptId = scriptId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getOrderNum() {
        return orderNum;
    }

    public void setOrderNum(Integer orderNum) {
        this.orderNum = orderNum;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(String events) {
        this.events = events;
    }

    public String getEventsBackup() {
        return eventsBackup;
    }

    public void setEventsBackup(String eventsBackup) {
        this.eventsBackup = eventsBackup;
    }
}
