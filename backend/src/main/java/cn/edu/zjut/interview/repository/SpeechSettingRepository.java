package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.SpeechSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** {@code speech_setting}：每种语音设置（asr / tts）一行，整份 JSON 存 payload。 */
public interface SpeechSettingRepository extends JpaRepository<SpeechSetting, Long> {

    /** 按种类查：业务键是 kind，唯一约束保证最多一条。 */
    Optional<SpeechSetting> findByKind(String kind);
}
