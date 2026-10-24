package cn.edu.zjut.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 队列测试的读状态工具：直接解析 {@code .agent/queue*.json}（不经过被测的私有方法）。
 *
 * <p>放在独立文件里是为了让 {@code StageQueueTest} 只读契约、不依赖反射；
 * {@code StageQueue} 本身是包内可见，测试与它在同一包下。
 */
final class StageQueueTestSupport {

    private StageQueueTestSupport() {
    }

    static List<StageQueue.Entry> readEntries(Path jsonFile) throws Exception {
        List<StageQueue.Entry> out = new ArrayList<>();
        if (!Files.isRegularFile(jsonFile)) {
            return out;
        }
        JsonNode root = new ObjectMapper().readTree(Files.readString(jsonFile, StandardCharsets.UTF_8));
        for (JsonNode node : root) {
            out.add(new StageQueue.Entry(
                    StageQueue.Mark.parse(node.path("mark").asText()),
                    SkillAgentRouter.TaskKind.parse(node.path("kind").asText()),
                    node.path("target").asText(),
                    node.path("registeredAt").asLong(),
                    node.path("existedAtRegister").asBoolean()));
        }
        return out;
    }
}
