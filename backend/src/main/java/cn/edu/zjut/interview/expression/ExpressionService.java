package cn.edu.zjut.interview.expression;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.ExpressionReport;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表达分析的取数层（<b>本项目扩展（课设指标 ④）</b>）。
 *
 * <p>职责只有两件：把 {@code interview_message} 里<b>真正属于求职者的话</b>挑出来，
 * 再给每条配上「相对上一句台词的时间差」，然后交给 {@link ExpressionAnalyzer}。
 * 算分规则全在分析器里，这里不做任何打分——两边分开才能给分析器写纯单元测试。
 *
 * <p><b>为什么要过滤旁白</b>：剧本引擎把旁白 / 剧情提示借用 {@code role=user} 落库
 * （见 {@code EngineContext#senderRoleId}），不过滤会把「请候选人说明技术方向」这种
 * 指令当成求职者的回答，字数和口头禅统计会被直接带偏。
 */
@Service
public class ExpressionService {

    private static final Logger log = LoggerFactory.getLogger(ExpressionService.class);

    /** 少于这么多次作答，统计意义太重（一次回答的平均值就是它自己），不做分析。 */
    private static final int MIN_USER_TURNS = 2;

    /**
     * 台词信封：{@code {情绪: 台词}} 或 {@code {旁白: 文本}}。
     *
     * <p>只匹配「整条消息被一对大括号包住」的情况——正文里作为修辞出现的括号
     * （如「我用 Spring 的 {拦截器}」）不该被剥掉。
     */
    private static final Pattern ENVELOPE =
            Pattern.compile("^\\s*\\{([^:：}]{1,16})[:：]\\s*(.+?)\\s*}\\s*", Pattern.DOTALL);

    private final InterviewSessionRepository sessionRepository;
    private final InterviewMessageRepository messageRepository;

    public ExpressionService(InterviewSessionRepository sessionRepository,
                             InterviewMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 计算一场面试的表达分析（只读，任何时候调用结果都一样）。
     *
     * @throws ResponseStatusException 404 场次不存在；400 自由对话场次或作答不足
     */
    @Transactional(readOnly = true)
    public ExpressionReport analyze(Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到场次：" + sessionId));

        // 与趋势、评估同一条口径：只有剧本场次才有「题目—回答」的结构，
        // 自由对话没有题单，句长与节奏的口径意义不大
        if (session.getScriptId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "自由对话场次不做表达分析（仅剧本面试支持）");
        }

        List<ExpressionAnalyzer.Utterance> utterances = collectUtterances(sessionId);
        if (utterances.size() < MIN_USER_TURNS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "至少需要 " + MIN_USER_TURNS + " 次作答才能做表达分析，当前只有 " + utterances.size() + " 次");
        }

        ExpressionReport report = ExpressionAnalyzer.analyze(sessionId, utterances);
        log.info("[Expression] 场次 {} 分析完成：{} 条作答，综合 {} 分", sessionId,
                report.sampleCount(), report.overallScore());
        return report;
    }

    /**
     * 把台词流还原成「一条回答 + 它与上一句的时间差」。
     *
     * <p>时间差用<b>相邻两条台词</b>的 {@code created_at} 算，而不是「上一个 assistant 行」：
     * 剧本场次里旁白也会插在中间，相邻行才是用户真正等过的那段时间。
     */
    private List<ExpressionAnalyzer.Utterance> collectUtterances(Long sessionId) {
        List<ExpressionAnalyzer.Utterance> utterances = new ArrayList<>();
        LocalDateTime previousAt = null;
        for (InterviewMessage message : messageRepository.findBySessionIdOrderBySeqAsc(sessionId)) {
            if (InterviewMessage.ROLE_SYSTEM.equals(message.getRole())) {
                continue;
            }
            LocalDateTime createdAt = message.getCreatedAt();
            if (!InterviewMessage.ROLE_USER.equals(message.getRole()) || isPromptLine(message)) {
                // 面试官台词 / 旁白：只用来更新「上一句」的时间锚点
                previousAt = createdAt;
                continue;
            }
            String text = stripEnvelope(message.getContent());
            if (text.isBlank()) {
                previousAt = createdAt;
                continue;
            }
            Long delayMs = delayMillis(previousAt, createdAt);
            utterances.add(new ExpressionAnalyzer.Utterance(text, delayMs));
            previousAt = createdAt;
        }
        return utterances;
    }

    /** 相邻两句的时间差（毫秒）；任一端没有时间戳就是 {@code null}（分析器按「缺项」处理）。 */
    private static Long delayMillis(LocalDateTime previous, LocalDateTime current) {
        if (previous == null || current == null) {
            return null;
        }
        long delay = current.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                - previous.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return delay >= 0 ? delay : null;
    }

    /** 旁白 / 剧情提示行：{@code msg_type} 标了，或历史数据只剩 {@code {旁白: ...}} 信封。 */
    private static boolean isPromptLine(InterviewMessage message) {
        String msgType = message.getMsgType();
        if ("narration".equals(msgType) || "plot".equals(msgType)) {
            return true;
        }
        String content = message.getContent();
        return content != null && content.strip().startsWith("{旁白:");
    }

    /** 剥掉 {@code {情绪: 台词}} 信封只留台词；不含信封的原样返回。 */
    private static String stripEnvelope(String content) {
        if (content == null) {
            return "";
        }
        Matcher matcher = ENVELOPE.matcher(content);
        if (matcher.matches()) {
            return matcher.group(2).strip();
        }
        return content.strip();
    }
}
