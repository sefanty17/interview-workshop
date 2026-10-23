package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code script:sound} 的 data，对应 {@code script_engine/responses.rs::SoundPayload}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoundPayload(String soundPath, Double duration) {
}
