package cn.edu.zjut.interview.speech.asr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话状态机（规格 V1 §4.4）：互斥开、幂等停、幂等取消、停完还能再开。
 *
 * <p>「幂等」是这里最要紧的契约：前端在切页面 / 关窗口 / 网络断开时都会补发 stop 与 cancel，
 * 若第二次调用就报错，用户会看到一堆「取消失败」的弹窗，而实际上没有任何东西出错。
 */
@SpringBootTest
@ActiveProfiles("test")
class AsrSessionServiceTest {

    @Autowired
    private AsrSessionService sessionService;

    @AfterEach
    void 清理会话() {
        sessionService.cancel();
    }

    @Test
    void 开启后状态里有会话() {
        AsrSessionService.ActiveSession session = sessionService.start(AsrSessionService.SOURCE_BUTTON);

        assertNotNull(session.sessionId());
        assertEquals("button", session.source());

        AsrSessionService.AsrStatus status = sessionService.status();
        assertEquals(session.sessionId(), status.sessionId());
        assertEquals("button", status.activeSource());
        assertEquals("qwen-asr", status.activeProvider());
        // 契约字段：vad_loaded 自 V2 恒 true（能量门控无模型文件，不存在加载失败）；
        // ptt_global_ok 恒 false（浏览器没有 OS 级全局热键）
        assertEquals(true, status.vadLoaded());
        assertEquals(false, status.pttGlobalOk());
        // provider 没配 key
        assertEquals(false, status.providerReady());
    }

    @Test
    void 重复开启抛SESSION_BUSY() {
        sessionService.start(AsrSessionService.SOURCE_BUTTON);

        AsrException e = assertThrows(AsrException.class,
                () -> sessionService.start(AsrSessionService.SOURCE_AUTO));

        assertEquals(AsrErrorKind.SESSION_BUSY, e.kind());
        assertEquals(409, e.status().value());
    }

    @Test
    void 停止是幂等的() {
        sessionService.start(AsrSessionService.SOURCE_AUTO);

        Optional<AsrSessionService.ActiveSession> first = sessionService.stop();
        assertTrue(first.isPresent());

        Optional<AsrSessionService.ActiveSession> second =
                assertDoesNotThrow(sessionService::stop);
        assertTrue(second.isEmpty());
        assertNull(sessionService.status().sessionId());
    }

    @Test
    void 取消是幂等的() {
        sessionService.start(AsrSessionService.SOURCE_BUTTON);

        assertDoesNotThrow(sessionService::cancel);
        assertDoesNotThrow(sessionService::cancel);
        assertNull(sessionService.status().sessionId());
    }

    @Test
    void 停止后可以再开启() {
        AsrSessionService.ActiveSession first = sessionService.start(AsrSessionService.SOURCE_BUTTON);
        sessionService.stop();

        AsrSessionService.ActiveSession second = sessionService.start(AsrSessionService.SOURCE_AUTO);

        assertEquals("auto", second.source());
        // 新会话必须是新 id：前端靠它区分「同一轮的残留事件」和「新一轮」
        assertTrue(!first.sessionId().equals(second.sessionId()));
    }
}
