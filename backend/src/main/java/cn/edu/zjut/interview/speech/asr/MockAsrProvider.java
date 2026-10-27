package cn.edu.zjut.interview.speech.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地模拟 provider：不连任何服务，固定返回一句文本。
 *
 * <p><b>它不是假数据的偷偷兜底</b>——它是注册表里一个显式的 provider，用户能在设置页
 * 看到并主动选中。选 {@code qwen-asr} 而没有 key 时依然报错，绝不会被悄悄替换成模拟结果：
 * 静默兜底会让「语音识别不工作」变成一个永远查不出来的问题（用户以为识别成功了）。
 *
 * <p>存在意义：没有阿里云百炼 Key 时也能把前端录音 → 上传 → 出文本这条链路跑通，
 * 以及给后端接口测试一个不依赖外网的 provider。
 *
 * <p>{@code app.speech.asr.mock-enabled=false} 可整体关掉（不想让用户看到它时）；
 * 默认开，因为本项目的默认部署就是无 Key 的演示环境。
 */
@Component
@ConditionalOnProperty(name = "app.speech.asr.mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockAsrProvider implements AsrProvider {

    private static final Logger log = LoggerFactory.getLogger(MockAsrProvider.class);

    public static final String ID = "mock-asr";
    public static final String DISPLAY = "本地模拟（无 key 联调用）";

    private static final String MOCK_TEXT = "这是一段模拟识别的文本，用于没有语音服务时跑通链路。";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AsrProviderInfo info() {
        return new AsrProviderInfo(
                ID,
                DISPLAY,
                "不连接任何语音服务，固定返回一段文本，用于联调前端录音链路",
                true,
                // 没有任何可配置项：它不连网、不需要 key 与端点。凭空造一个输入框
                // 只会让人以为这里能调什么，而实际上改什么都不生效
                List.of(),
                List.of());
    }

    @Override
    public AsrResult recognize(byte[] wav, AsrOptions options) {
        log.debug("[ASR] mock 识别：收到 {} 字节音频", wav == null ? 0 : wav.length);
        return new AsrResult(MOCK_TEXT, "zh", 0.99f, ID);
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }
}
