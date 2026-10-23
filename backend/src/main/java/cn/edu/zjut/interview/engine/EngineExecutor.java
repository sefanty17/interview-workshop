package cn.edu.zjut.interview.engine;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 引擎的 SSE 工作线程池。
 *
 * <p>SSE 写是阻塞的，每个请求占一个线程；个人项目 4 个并发足够（与
 * {@code ChatService} 的 {@code chat-sse-*} 池同规格，但独立命名，便于分辨日志来源）。
 */
@Component
public class EngineExecutor {

    private final ExecutorService executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "script-engine-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    public void submit(Runnable task) {
        executor.submit(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
