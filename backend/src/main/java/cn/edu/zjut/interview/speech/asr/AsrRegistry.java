package cn.edu.zjut.interview.speech.asr;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * provider 注册表：对应上游 {@code registry.rs::list_provider_info} / {@code get_provider}。
 *
 * <p>「有哪些 provider」「某 provider 有哪些模型」的单一入口。新增 provider 只需加一个
 * {@link AsrProvider} 的 Spring Bean，本类与前端都不用改。
 *
 * <p>顺序是<strong>显式固定</strong>的，不依赖 Spring 的注入顺序：设置页下拉的第一项
 * 就是用户的默认选择，若某次重构让 mock 排到第一，所有人都会莫名其妙地用上模拟识别。
 */
@Component
public class AsrRegistry {

    /**
     * 设置页下拉的固定顺序（也是 {@link AsrSettings#defaults()} 填占位配置的顺序）。
     *
     * <p>mock 必须排最后：它是联调用的，不该成为默认项。
     */
    public static final List<String> PROVIDER_ORDER = List.of("qwen-asr", "llama-asr", "mock-asr");

    private final List<AsrProvider> providers;

    public AsrRegistry(List<AsrProvider> providers) {
        List<AsrProvider> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparingInt(provider -> {
            int index = PROVIDER_ORDER.indexOf(provider.id());
            // 未知 id（未来新增但没登记顺序的 provider）排到最后，且保持相对顺序不变
            return index < 0 ? Integer.MAX_VALUE : index;
        }));
        this.providers = List.copyOf(sorted);
    }

    /** 全部 provider 的元信息，顺序见 {@link #PROVIDER_ORDER}。 */
    public List<AsrProviderInfo> listProviderInfo() {
        return providers.stream().map(AsrProvider::info).toList();
    }

    /**
     * 按 id 取 provider；找不到抛 {@link AsrErrorKind#PROVIDER_NOT_FOUND} / 404。
     *
     * <p>这是「资源不存在」语义：路径或配置里引用了不存在的 provider。请求体里写了
     * 未知 id 属于参数错误（400），由调用方自己判断，不走这里。
     */
    public AsrProvider require(String providerId) {
        for (AsrProvider provider : providers) {
            if (provider.id().equals(providerId)) {
                return provider;
            }
        }
        throw AsrException.providerNotFound(providerId);
    }

    /** 该 id 是否已注册（用于「设置里存着已被删掉的 provider」这种野值判定）。 */
    public boolean contains(String providerId) {
        return providers.stream().anyMatch(provider -> provider.id().equals(providerId));
    }

    /** 某 provider 在某地域下的模型清单；未知 provider 抛 404。 */
    public List<AsrModelInfo> models(String providerId, String region) {
        return require(providerId).models(region);
    }
}
