package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.domain.PlayerIdentity;
import cn.edu.zjut.interview.dto.PlayerIdentityDto;
import cn.edu.zjut.interview.service.PlayerIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我的身份」CRUD（对齐 {@code crates/ling-chat-main/src/api/player_identity.rs}）。
 *
 * <p>路径用本项目风格（REST 复数资源）。删除有两条护栏：
 * <ul>
 *   <li>{@code interview_message} 有引用 → 409 + {@code IDENTITY_IN_USE}</li>
 *   <li>当前身份 → 409 + {@code IDENTITY_IS_CURRENT}（本项目扩展）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/player-identities")
public class PlayerIdentityController {

    private final PlayerIdentityService service;

    public PlayerIdentityController(PlayerIdentityService service) {
        this.service = service;
    }

    /** 列表；库为空时展示一条默认身份（{@code id="me"}），不落库。 */
    @GetMapping
    public List<PlayerIdentityDto.PlayerIdentitySummary> list() {
        List<PlayerIdentity> all = service.list();
        if (all.isEmpty()) {
            return List.of(new PlayerIdentityDto.PlayerIdentitySummary(
                    PlayerIdentityService.DEFAULT_IDENTITY_ID,
                    PlayerIdentity.DEFAULT_NAME, "", "", false, 0, true));
        }
        List<PlayerIdentityDto.PlayerIdentitySummary> out = new ArrayList<>(all.size());
        for (PlayerIdentity each : all) {
            Map<String, String> relations = new LinkedHashMap<>(service.relationsOf(each.getIdentityId()));
            int relationCount = (int) relations.values().stream()
                    .filter(v -> v != null && !v.trim().isEmpty())
                    .count();
            boolean current = Boolean.TRUE.equals(each.getIsCurrent());
            out.add(new PlayerIdentityDto.PlayerIdentitySummary(
                    each.getIdentityId(),
                    each.displayName(),
                    each.getSubtitle() == null ? "" : each.getSubtitle(),
                    each.getPrompt() == null ? "" : each.getPrompt(),
                    each.getAvatar() != null && !each.getAvatar().isBlank(),
                    relationCount,
                    current));
        }
        return out;
    }

    /** 详情（含 {@code relations} Map）。 */
    @GetMapping("/{identityId}")
    public ResponseEntity<Object> detail(@PathVariable String identityId) {
        return service.get(identityId)
                .<ResponseEntity<Object>>map(card -> ResponseEntity.ok(view(card)))
                .orElseGet(() -> notFound(identityId));
    }

    /** 新建（body 里 {@code identityId} 为空）。 */
    @PostMapping
    public PlayerIdentityDto.PlayerIdentityView create(@RequestBody PlayerIdentityDto.PlayerIdentityView body) {
        return view(service.save(toEntity(body, null)));
    }

    /** 更新（路径上的 id 为准）。 */
    @PutMapping("/{identityId}")
    public ResponseEntity<Object> update(@PathVariable String identityId,
                                         @RequestBody PlayerIdentityDto.PlayerIdentityView body) {
        if (service.get(identityId).isEmpty()) {
            return notFound(identityId);
        }
        return ResponseEntity.ok(view(service.save(toEntity(body, identityId))));
    }

    /** 删除；被引用 → 409 {@code IDENTITY_IN_USE}，不存在 → 404，成功 → 200 {@code {"deleted":true}}。 */
    @DeleteMapping("/{identityId}")
    public ResponseEntity<Object> delete(@PathVariable String identityId) {
        try {
            service.delete(identityId);
        } catch (PlayerIdentityService.IdentityDeleteBlocked blocked) {
            if ("IDENTITY_NOT_FOUND".equals(blocked.code())) {
                return notFound(identityId);
            }
            return error(HttpStatus.CONFLICT, blocked.code(), blocked.getMessage());
        }
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /** 设为当前身份（返回 200 + 当前身份）。 */
    @PostMapping("/{identityId}/switch")
    public ResponseEntity<Object> switchTo(@PathVariable String identityId) {
        return service.switchTo(identityId)
                .<ResponseEntity<Object>>map(card -> ResponseEntity.ok(view(card)))
                .orElseGet(() -> notFound(identityId));
    }

    private PlayerIdentityDto.PlayerIdentityView view(PlayerIdentity card) {
        return new PlayerIdentityDto.PlayerIdentityView(
                card.getIdentityId(),
                card.displayName(),
                card.getSubtitle() == null ? "" : card.getSubtitle(),
                card.getPrompt() == null ? "" : card.getPrompt(),
                card.getAvatar(),
                service.relationsOf(card.getIdentityId()));
    }

    private static PlayerIdentity toEntity(PlayerIdentityDto.PlayerIdentityView body, String forcedId) {
        PlayerIdentity card = new PlayerIdentity();
        if (forcedId != null) {
            card.setIdentityId(forcedId);
        } else if (body != null && body.identityId() != null && !body.identityId().isBlank()) {
            card.setIdentityId(body.identityId());
        }
        card.setName(body == null || body.name() == null ? "" : body.name());
        card.setSubtitle(body == null || body.subtitle() == null ? "" : body.subtitle());
        card.setPrompt(body == null || body.prompt() == null ? "" : body.prompt());
        card.setAvatar(body == null ? null : body.avatar());
        card.setRelationMap(body == null || body.relations() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(body.relations()));
        return card;
    }

    private static ResponseEntity<Object> notFound(String identityId) {
        return error(HttpStatus.NOT_FOUND, "not_found", "身份不存在：" + identityId);
    }

    private static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
