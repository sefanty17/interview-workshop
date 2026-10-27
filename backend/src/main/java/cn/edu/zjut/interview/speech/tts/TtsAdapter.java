package cn.edu.zjut.interview.speech.tts;

/**
 * 一个 TTS 引擎的最小接口，对应上游 {@code provider.rs::TtsAdapter}（只保留同步的
 * {@code generate_voice}）。
 *
 * <p>不把「参数读取」放进接口：上游每个适配器还有 {@code get_params} 与
 * {@code generate_voice_stream}，本项目都不需要——前者是给 Rust 端调试面板用的，
 * 后者只被 Fish S2 / IndexTTS2 实现，留到真正接流式时再加，免得现在写一堆返回 null 的空实现。
 *
 * <p>实现类<strong>不是 Spring Bean</strong>：由 {@link TtsService} 每次合成时按当前设置
 * 现造（见 {@code TtsService#createAdapter}），这样改了地址/密钥下一次立刻生效。
 */
public interface TtsAdapter {

    /** 引擎类型，与前端 {@code ttsType} 的取值逐字一致（sbv2 / sbv2api / bv2 / vits）。 */
    String type();

    /**
     * 合成一段语音，返回原始音频字节（格式由各引擎自己的参数决定）。
     *
     * @param text 待合成文本
     * @param emo  情感标记，只有 IndexTTS2 与 Fish S2 使用，其余适配器忽略
     */
    byte[] generate(String text, String emo);
}
