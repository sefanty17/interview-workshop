package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code script:music} 的 data，对应 {@code script_engine/responses.rs::MusicPayload}。
 *
 * @param playbackSpeed 播放速度倍率（1.0 原速）；没写就不序列化，前端按 1.0 处理
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MusicPayload(String musicPath, Double playbackSpeed, Double duration) {
}
