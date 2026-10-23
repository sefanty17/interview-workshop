package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 面试流程模板，对应 DDL {@code interview_script}（对齐 LingChat 的 story_config.yaml）。
 *
 * <p>外键 interviewer_id 与 interview_chapter.script_id 一样，这里只映射成 Long，
 * 不建 JPA 关联对象，避免实体图变重；需要关联时由 service 层按 id 再查。
 */
@Entity
@Table(name = "interview_script")
public class InterviewScript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", length = 64, nullable = false, unique = true)
    private String code;

    @Column(name = "script_name", length = 255, nullable = false)
    private String scriptName;

    @Column(name = "intro_chapter", length = 255, nullable = false)
    private String introChapter;

    @Column(name = "description", columnDefinition = "CLOB")
    private String description;

    @Column(name = "recommand_start", length = 255)
    private String recommandStart;

    @Column(name = "interviewer_id")
    private Long interviewerId;

    @Column(name = "order_num")
    private Integer orderNum = 0;

    @Column(name = "trigger_mode", length = 32)
    private String triggerMode = "manual";

    @Column(name = "job_code", length = 32)
    private String jobCode;

    /** 列名就是 DDL 里的 level。 */
    @Column(name = "level", length = 32)
    private String level;

    @Column(name = "company_style", length = 64)
    private String companyStyle;

    @Column(name = "script_settings", columnDefinition = "CLOB")
    private String scriptSettings;

    /**
     * 本剧本的角色目录名（JSON 数组字符串，如 {@code ["MAIN"]}），
     * 对齐 LingChat 剧本目录下 {@code characters/} 的子目录。
     */
    @Column(name = "characters", columnDefinition = "CLOB")
    private String characters;

    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getScriptName() {
        return scriptName;
    }

    public void setScriptName(String scriptName) {
        this.scriptName = scriptName;
    }

    public String getIntroChapter() {
        return introChapter;
    }

    public void setIntroChapter(String introChapter) {
        this.introChapter = introChapter;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRecommandStart() {
        return recommandStart;
    }

    public void setRecommandStart(String recommandStart) {
        this.recommandStart = recommandStart;
    }

    public Long getInterviewerId() {
        return interviewerId;
    }

    public void setInterviewerId(Long interviewerId) {
        this.interviewerId = interviewerId;
    }

    public Integer getOrderNum() {
        return orderNum;
    }

    public void setOrderNum(Integer orderNum) {
        this.orderNum = orderNum;
    }

    public String getTriggerMode() {
        return triggerMode;
    }

    public void setTriggerMode(String triggerMode) {
        this.triggerMode = triggerMode;
    }

    public String getJobCode() {
        return jobCode;
    }

    public void setJobCode(String jobCode) {
        this.jobCode = jobCode;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getCompanyStyle() {
        return companyStyle;
    }

    public void setCompanyStyle(String companyStyle) {
        this.companyStyle = companyStyle;
    }

    public String getScriptSettings() {
        return scriptSettings;
    }

    public void setScriptSettings(String scriptSettings) {
        this.scriptSettings = scriptSettings;
    }

    public String getCharacters() {
        return characters;
    }

    public void setCharacters(String characters) {
        this.characters = characters;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
