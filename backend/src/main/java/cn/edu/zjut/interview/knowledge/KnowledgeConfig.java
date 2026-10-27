package cn.edu.zjut.interview.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量化实现的装配：按配置在「本地假向量」与「OpenAI 兼容服务」之间二选一。
 *
 * <p>配了 {@code provider=openai} 但缺 base-url/api-key 时<b>只告警并回落 hashing</b>，
 * 不让整个后端起不来——知识库是增强功能，缺配置不应该阻断面试主流程。
 */
@Configuration
public class KnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConfig.class);

    @Bean
    EmbeddingProvider embeddingProvider(KnowledgeEmbeddingProperties props) {
        if ("openai".equalsIgnoreCase(props.getProvider())) {
            boolean missingBaseUrl = props.getBaseUrl() == null || props.getBaseUrl().isBlank();
            boolean missingApiKey = props.getApiKey() == null || props.getApiKey().isBlank();
            if (missingBaseUrl || missingApiKey) {
                log.warn("[Knowledge] embedding 走 openai 但未配置 base-url/api-key，回落 hashing");
            } else {
                return new OpenAiCompatibleEmbeddingProvider(
                        props.getBaseUrl(), props.getApiKey(), props.getModel(), props.getTimeoutSeconds());
            }
        }
        return new HashingEmbeddingProvider();
    }
}
