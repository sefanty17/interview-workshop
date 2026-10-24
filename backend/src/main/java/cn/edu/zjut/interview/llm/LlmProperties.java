package cn.edu.zjut.interview.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 配置，对应 {@code application.yml} 的 {@code app.llm.*}（规格 B3-BE §三）。
 *
 * <p>Key 只存在后端配置文件里，前端拿不到。
 */
@Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-reasoner";
    private double temperature = 1.0;
    private int maxTokens = 800;
    private int timeoutSeconds = 60;

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

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
