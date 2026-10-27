package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 语音线（ASR / TTS）设置的整份快照，对应 DDL {@code speech_setting}。
 *
 * <p>为什么整份存 JSON 而不像 {@code user_settings} 那样一个标量一行：
 * 语音设置是嵌套结构（{@code provider_configs} 是「provider id → 凭据对象」的映射，
 * TTS 的每种适配器又各有一套参数），拆成扁平行就得在服务层来回拼装，而且每加一个
 * 子字段都要改表结构。存一列 JSON 则新增字段只需改 DTO，库不用动。
 *
 * <p>代价是数据库无法按设置项查询/约束——但设置从来只按 {@code kind} 整份读写，
 * 没有「查所有把 vad_silence_ms 设成 100 的用户」这种需求。
 *
 * <p>{@code kind} 只有 {@code asr} / {@code tts} 两个值，唯一约束保证每种设置只留一行：
 * 设置页保存是「整体覆盖」语义，多行会让「读哪一行」变成随机行为。
 */
@Entity
@Table(name = "speech_setting")
public class SpeechSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 设置种类：{@code asr} / {@code tts}，见 {@code SpeechSettingService.KIND_ASR}。 */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /** 整份设置的 JSON 文本（嵌套结构，不是扁平 key-value）。 */
    @Column(name = "payload", nullable = false, columnDefinition = "CLOB")
    private String payload;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public SpeechSetting() {
    }

    public SpeechSetting(Long id, String kind, String payload, LocalDateTime updatedAt) {
        this.id = id;
        this.kind = kind;
        this.payload = payload;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
