package cn.edu.zjut.interview.speech.asr;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * provider 注册表语义（规格 V1 §4.2）。
 *
 * <p>用纯单元测试而不是启动 Spring 上下文：注册表的价值就在于「顺序不依赖注入顺序」，
 * 故意把 provider 打乱顺序传进去，才测得到排序逻辑本身。
 */
class AsrRegistryTest {

    /** 构造顺序与期望顺序完全相反，任何「靠注入顺序」的实现都会在这里翻车。 */
    private static AsrRegistry shuffledRegistry() {
        return new AsrRegistry(List.of(new MockAsrProvider(), new LlamaAsrProvider(), new QwenAsrProvider()));
    }

    @Test
    void 三个provider顺序固定() {
        List<String> ids = shuffledRegistry().listProviderInfo().stream().map(AsrProviderInfo::id).toList();

        assertEquals(List.of("qwen-asr", "llama-asr", "mock-asr"), ids);
    }

    @Test
    void 每个provider的configFields不重名() {
        for (AsrProviderInfo info : shuffledRegistry().listProviderInfo()) {
            Set<String> keys = new HashSet<>();
            for (AsrConfigField field : info.configFields()) {
                assertTrue(keys.add(field.key()), info.id() + " 的字段 " + field.key() + " 重复");
            }
        }
    }

    @Test
    void qwen有地域列表llama没有() {
        AsrRegistry registry = shuffledRegistry();

        assertFalse(registry.require("qwen-asr").info().regions().isEmpty());
        assertTrue(registry.require("llama-asr").info().regions().isEmpty());
    }

    @Test
    void require未知id抛PROVIDER_NOT_FOUND() {
        AsrException e = assertThrows(AsrException.class, () -> shuffledRegistry().require("no-such-asr"));

        assertEquals(AsrErrorKind.PROVIDER_NOT_FOUND, e.kind());
        assertEquals(404, e.status().value());
    }

    @Test
    void effectiveApiKey按地域取() {
        AsrProviderConfig config = new AsrProviderConfig();
        config.getApiKeys().put("cn-beijing", "a");
        config.getApiKeys().put("ap-southeast-1", "b");

        config.setRegion("ap-southeast-1");
        assertEquals("b", config.effectiveApiKey());

        // 空地域 = 默认地域（北京），与端点派生用同一个键，否则会出现「端点按北京、Key 查不到」
        config.setRegion("");
        assertEquals("a", config.effectiveApiKey());

        // 已配地域但该地域没填 Key：回退顶层历史字段，不能静默变空
        config.setRegion("ap-southeast-1");
        config.getApiKeys().clear();
        config.setApiKey("legacy");
        assertEquals("legacy", config.effectiveApiKey());
    }
}
