package cn.edu.zjut.interview.speech.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * DashScope 接入地域，1:1 移植 {@code ai_service/asr/region.rs}。
 *
 * <p>各地域的域名、API Key、模型列表三者独立、不能混用（阿里云「选择地域」文档）。
 * 本枚举是端点默认值的<strong>唯一真相</strong>：provider、设置页元数据、前端
 * 切地域时的端点预设都从这里派生，别处不许硬编码 host。
 *
 * <p>端点串一律照抄上游，不要自己拼：业务空间专属域名与旧 DashScope 域名的
 * 有效期不同（旧域名 2026-09-30 起不再支持新特性），写错就是「配上 key 也连不上」。
 */
public enum DashScopeRegion {

    /** 华北2（北京）。 */
    CN_BEIJING("cn-beijing", "华北2（北京）", "dashscope.aliyuncs.com"),
    /** 新加坡（国际站）。 */
    AP_SOUTHEAST_1("ap-southeast-1", "新加坡（国际站）", "dashscope-intl.aliyuncs.com");

    private static final Logger log = LoggerFactory.getLogger(DashScopeRegion.class);

    /** 全部地域。顺序即设置页下拉的展示顺序。 */
    public static final List<DashScopeRegion> ALL = List.of(CN_BEIJING, AP_SOUTHEAST_1);

    /**
     * 默认地域：空值 / 未知值一律回退到它。
     *
     * <p>之所以能安心回退：地域只影响「连哪个域名」，回退成北京最坏是请求打错站点并
     * 明确报错；而解析失败若抛出，整个设置页会因为一个拼错的地域 id 打不开。
     */
    public static final DashScopeRegion DEFAULT = CN_BEIJING;

    private final String id;
    private final String label;
    private final String host;

    DashScopeRegion(String id, String label, String host) {
        this.id = id;
        this.label = label;
        this.host = host;
    }

    /** 持久化 id，写进 {@code provider_configs[id].region}，也是 {@code api_keys} 的键。 */
    public String id() {
        return id;
    }

    /** 设置页显示名。 */
    public String label() {
        return label;
    }

    /** DashScope 接入域名（不含协议）。 */
    public String host() {
        return host;
    }

    /** 非实时（同步）识别端点：{@code multimodal-generation}。 */
    public String httpEndpoint() {
        return "https://" + host + "/api/v1/services/aigc/multimodal-generation/generation";
    }

    /** 实时识别 WebSocket 端点。 */
    public String wsEndpoint() {
        return "wss://" + host + "/api-ws/v1/inference";
    }

    /**
     * 宽松解析持久化值：空串 / 未知值一律回退 {@link #DEFAULT} 并 warn。
     *
     * <p>刻意不做成 {@code @JsonCreator}：反序列化抛异常会让整份设置被
     * {@code SpeechSettingService.read} 判为损坏而回退默认值，一个拼错的地域
     * 不该有「连带丢掉 API Key」的破坏力。
     */
    public static DashScopeRegion parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return DEFAULT;
        }
        for (DashScopeRegion region : ALL) {
            if (region.id.equals(value)) {
                return region;
            }
        }
        log.warn("[ASR] 未知地域 '{}'，回退 {}", value, DEFAULT.id);
        return DEFAULT;
    }

    /**
     * 按端点 URL 反推地域（老数据迁移用）：URL 含该地域的 host 或地域 id 即命中。
     *
     * <p>覆盖两类写法：旧 DashScope 域名（{@code dashscope-intl.aliyuncs.com}）与
     * 业务空间专属域名（{@code {WorkspaceId}.ap-southeast-1.maas.aliyuncs.com}）。
     * 无法识别（自建代理等）返回 {@code empty}，由调用方决定回退策略。
     */
    public static Optional<DashScopeRegion> fromEndpointHint(String endpoint) {
        String url = endpoint == null ? "" : endpoint.trim().toLowerCase(Locale.ROOT);
        if (url.isEmpty()) {
            return Optional.empty();
        }
        for (DashScopeRegion region : ALL) {
            if (url.contains(region.host) || url.contains(region.id)) {
                return Optional.of(region);
            }
        }
        return Optional.empty();
    }
}
