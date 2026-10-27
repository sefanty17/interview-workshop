package cn.edu.zjut.interview.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量化配置，对应 {@code application.yml} 的 {@code app.knowledge.embedding.*}。
 *
 * <p>默认 {@code provider=hashing}：不配任何 key 也能启动、能演示。
 * 换成 {@code openai} 时 base-url/api-key 从环境变量读，密钥不入库、不进前端。
 */
@Component
@ConfigurationProperties(prefix = "app.knowledge.embedding")
public class KnowledgeEmbeddingProperties {

    /** {@code hashing} 或 {@code openai}；未知值一律按 hashing 处理，避免启动失败。 */
    private String provider = "hashing";

    private String baseUrl = "";

    private String apiKey = "";

    /** 只有走 openai 时才有意义；hashing 的模型名固定是 hashing-512。 */
    private String model = "bge-m3";

    private int timeoutSeconds = 20;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
