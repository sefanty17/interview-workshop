package cn.edu.zjut.interview.config;

import cn.edu.zjut.interview.domain.UserSetting;
import cn.edu.zjut.interview.repository.UserSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置注册表 / 校验 / 落库回落（规格 B4-BE §1.4、§五 1）。
 *
 * <p>覆盖四件事：默认值回落、范围校验、未知 key、options 非法值；
 * 外加「换一个 ConfigService 实例读同一张表仍拿到改后的值」（重启生效的等价验证——
 * 每次调用都查库，没有内存缓存，所以新实例读到的就是重启后读到的）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConfigServiceTest {

    @Autowired
    private ConfigService configService;

    @Autowired
    private UserSettingRepository userSettingRepository;

    @Test
    void treeHasTwoCategoriesFourSubcategoriesAndSeventeenDefaultValues() {
        userSettingRepository.deleteAll();
        ConfigTree tree = configService.buildTree();

        assertEquals(2, tree.categories().size(), "2 个顶层分类");
        assertEquals(List.of("llm", "features"),
                tree.categories().stream().map(ConfigTree.Category::id).toList());
        assertEquals(List.of("advanced"),
                tree.categories().get(0).subcategories().stream().map(ConfigTree.Subcategory::id).toList());
        assertEquals(List.of("chat-enhance", "interface", "chat-rhythm"),
                tree.categories().get(1).subcategories().stream().map(ConfigTree.Subcategory::id).toList());

        int total = tree.categories().stream()
                .flatMap(category -> category.subcategories().stream())
                .mapToInt(sub -> sub.settings().size())
                .sum();
        assertEquals(17, total, "第一批 17 个 key（audio.volume_character 已删）");

        for (ConfigTree.Category category : tree.categories()) {
            for (ConfigTree.Subcategory sub : category.subcategories()) {
                assertFalse(sub.settings().isEmpty(), "不建空壳子分类：" + sub.id());
                for (ConfigSetting setting : sub.settings()) {
                    assertEquals(setting.defaultValue(), setting.value(),
                            setting.key() + " 没有覆盖值时必须是默认值");
                    assertTrue(List.of(ConfigSetting.TYPE_TEXT, ConfigSetting.TYPE_NUMBER,
                            ConfigSetting.TYPE_BOOL).contains(setting.type()),
                            setting.key() + " 的 type 只能是 text/number/bool：" + setting.type());
                }
            }
        }
    }

    @Test
    void saveAllThenReadBack() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ConfigKey.LLM_TEMPERATURE, "1.5");
        values.put(ConfigKey.CHAT_REPLY_COUNT, "4");
        configService.saveAll(values);

        assertEquals(1.5, configService.getDouble(ConfigKey.LLM_TEMPERATURE));
        assertEquals(4, configService.getInt(ConfigKey.CHAT_REPLY_COUNT));
        assertEquals("1.5", find(configService.buildTree(), ConfigKey.LLM_TEMPERATURE).value());

        // 模拟重启：新实例（无内存状态）读同一张表，拿到的还是落库后的值
        ConfigService restarted = new ConfigService(userSettingRepository);
        assertEquals(1.5, restarted.getDouble(ConfigKey.LLM_TEMPERATURE));
        assertEquals(4, restarted.getInt(ConfigKey.CHAT_REPLY_COUNT));
    }

    @Test
    void outOfRangeValueIsRejectedWithRangeInMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> configService.saveAll(Map.of(ConfigKey.LLM_TEMPERATURE, "9")));

        assertTrue(error.getMessage().contains("温度"), error.getMessage());
        assertTrue(error.getMessage().contains("0.0–2.0"), error.getMessage());
        assertFalse(userSettingRepository.existsById(ConfigKey.LLM_TEMPERATURE), "校验失败不许落库");
    }

    @Test
    void intSettingRejectsNonIntegerAndOutOfRange() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> configService.saveAll(Map.of(ConfigKey.CHAT_REPLY_COUNT, "abc")))
                .getMessage().contains("必须是整数"));

        // 整数项不接受小数（type 合并成 number 后行为必须不变）
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> configService.saveAll(Map.of(ConfigKey.LLM_TIMEOUT_SECS, "1.5")))
                .getMessage().contains("必须是整数"));

        String message = assertThrows(IllegalArgumentException.class,
                () -> configService.saveAll(Map.of(ConfigKey.LLM_TIMEOUT_SECS, "400"))).getMessage();
        assertTrue(message.contains("5–300"), message);
    }

    @Test
    void boolSettingRejectsOtherText() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> configService.saveAll(Map.of(ConfigKey.CHAT_INLINE_ACTION, "yes")));
        assertTrue(error.getMessage().contains("true"), error.getMessage());
    }

    @Test
    void unknownKeyIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> configService.saveAll(Map.of("foo.bar", "1")));
        assertTrue(error.getMessage().contains("未知配置项"), error.getMessage());
        assertTrue(error.getMessage().contains("foo.bar"), error.getMessage());
    }

    /**
     * options 校验：第一批 17 个 key 里没有带 options 的项（原版也没有 ENUM 类型），
     * 所以直接造一个带 options 的 text 定义做校验，保证后续批次加下拉项时校验逻辑是通的。
     */
    @Test
    void enumSettingRejectsValueOutsideOptions() {
        ConfigSetting provider = ConfigSetting.enumSetting("llm.provider", "llm", "Provider", "deepseek",
                List.of("deepseek", "openai"), "测试用", true);

        assertTrue(ConfigService.isValid(provider, "deepseek"));
        assertTrue(ConfigService.isValid(provider, "openai"));
        assertFalse(ConfigService.isValid(provider, "anthropic"));
        assertFalse(ConfigService.isValid(provider, ""));

        // 不带 options 的 text 项不受影响，任意文本都合法（对照组，防止类型判断写反）
        assertTrue(ConfigService.isValid(ConfigService.definition(ConfigKey.LLM_MODEL), "deepseek-reasoner"));
    }

    @Test
    void corruptStoredValueFallsBackToDefault() {
        // 手工写一条越界值（模拟直接改库），读取侧必须回落默认值而不是把 9 交给 LLM
        userSettingRepository.save(new UserSetting(ConfigKey.LLM_TEMPERATURE, "9", LocalDateTime.now()));

        assertEquals(1.0, configService.getDouble(ConfigKey.LLM_TEMPERATURE));
        assertEquals("1.0", find(configService.buildTree(), ConfigKey.LLM_TEMPERATURE).value());
    }

    @Test
    void missingRowUsesDefaultValue() {
        assertEquals(3, configService.getInt(ConfigKey.CHAT_REPLY_COUNT));
        assertEquals("deepseek-chat", configService.get(ConfigKey.LLM_MODEL));
        assertEquals("", configService.get(ConfigKey.DISPLAY_FONT_FAMILY));
        assertFalse(configService.getBoolean(ConfigKey.LLM_ENABLE_THINKING));
    }

    /**
     * 消灭假开关的守卫（规格 S-2+3 §A6）：每个注册的 key 必须满足三条之一——
     * 有真实消费点（{@link ConfigService#CONSUMED_KEYS}）、标了本项目扩展、description 写明「仅存值」。
     *
     * <p>新增配置项时忘了维护这三样，这条会点名报出 key。
     */
    @Test
    void 每个配置键都必须有消费点或明确标注() {
        for (ConfigSetting s : ConfigService.allSettings()) {
            boolean ok = ConfigService.CONSUMED_KEYS.contains(s.key())
                    || s.extension()
                    || s.description().contains("仅存值");
            assertTrue(ok, "配置键既无消费点也未标注：" + s.key());
        }
    }

    private static ConfigSetting find(ConfigTree tree, String key) {
        return tree.categories().stream()
                .flatMap(category -> category.subcategories().stream())
                .flatMap(sub -> sub.settings().stream())
                .filter(setting -> setting.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("配置树里没有 " + key));
    }
}
