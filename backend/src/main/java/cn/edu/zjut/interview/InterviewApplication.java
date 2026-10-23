package cn.edu.zjut.interview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 面试工坊后端启动类。
 *
 * <p>B0 批只做骨架：H2 建表 + 种子数据 + 6 张表的 JPA 映射 + /api/health。
 * 提示词拼装（prompt）、流式分句（stream）、LLM 调用（llm）留给后续批次。
 */
@SpringBootApplication
public class InterviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewApplication.class, args);
    }
}
