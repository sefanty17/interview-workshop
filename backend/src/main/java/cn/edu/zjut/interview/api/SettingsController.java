package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.config.ConfigTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设置接口（规格 B4-BE §二），对应原版 {@code api/settings.rs} 的
 * {@code get_settings_tree} / {@code save_settings}。
 *
 * <pre>
 * GET /api/settings/tree  → 配置树（分组 → 设置项，含当前值）
 * PUT /api/settings       ← {"llm.temperature":"1.2","chat.reply_count":"4"}
 *                           校验失败 → 400 + {"error":"温度必须在 0.0–2.0 之间"}
 * </pre>
 *
 * <p>后端是权威：{@code user_settings} 里的值只由这个接口写，前端 localStorage 只当离线缓存。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final ConfigService configService;

    public SettingsController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/tree")
    public ConfigTree tree() {
        return configService.buildTree();
    }

    @PutMapping
    public Map<String, String> save(@RequestBody(required = false) Map<String, String> values) {
        configService.saveAll(values);
        return Map.of("message", "配置已保存");
    }

    /**
     * 校验失败统一转 400，body 形如 {@code {"error": "温度必须在 0.0–2.0 之间"}}：
     * 前端直接把这段文案弹给用户，不再自己拼「保存失败」这种没信息量的提示。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onIllegalArgument(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "配置不合法" : e.getMessage();
        log.info("[SettingsController] 拒绝保存：{}", message);
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
