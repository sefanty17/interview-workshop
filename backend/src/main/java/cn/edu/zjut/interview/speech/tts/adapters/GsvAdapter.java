package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsErrorKind;
import cn.edu.zjut.interview.speech.tts.TtsException;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GPT-SoVITS 适配器，对应上游 {@code adapters/gsv.rs}。一个类服务两个前端类型：
 * {@value #TYPE_GSV} 与 {@value #TYPE_GPT_SOVITS}。
 *
 * <p>为什么合并：两者打的是同一个服务的同一个端点（{@code POST /tts}），上游也只有
 * 一个 {@code gsv.rs}。唯一差别在前端表单——{@code gpt-sovits} 把地址拆成
 * {@code apiUrl} + {@code port} 两个输入框，{@code gsv} 的 {@code apiUrl} 自带端口，
 * 所以构造时传 {@code type} 决定读哪段配置，其余逻辑一字不差。
 *
 * <p>协议：先按需 {@code /set_gpt_weights} + {@code /set_sovits_weights} 加载权重
 * （GET，参数在查询串），再 {@code POST /tts}（JSON）。
 *
 * <p>与上游的差异（每一处都是有意为之，不是抄漏）：
 * <ul>
 *   <li><b>串行化用 static 锁</b>：上游 {@code request_lock} 是适配器实例字段，
 *       因为上游的适配器实例是长期存活的；本项目 {@code TtsService#createAdapter}
 *       每次合成现造一个实例，实例锁等于没锁。见 {@link #LOCKS}；</li>
 *   <li><b>模型「路径变了就重载」</b>：上游用 {@code OnceCell}，权重只 set 一次、
 *       改完模型路径必须重启进程。这里用 {@link #LOADED} 记住上次加载的组合。见
 *       {@link #ensureModelLoaded}；</li>
 *   <li><b>六情绪参考语音（{@code emo_prompts}）不接</b>：见 {@link #generate} 的说明。</li>
 * </ul>
 */
public class GsvAdapter implements TtsAdapter {

    /** 前端 index 11：单一 apiUrl（自带端口）、单组参考音频。 */
    public static final String TYPE_GSV = "gsv";

    /** 前端 index 1：apiUrl + 独立 port 输入框、单组参考音频。 */
    public static final String TYPE_GPT_SOVITS = "gpt-sovits";

    /**
     * 按 type 的串行锁。
     *
     * <p>上游 {@code gsv.rs:48-49} 的 {@code request_lock} 注释写的是
     * 「GPT-SoVITS/ROCm cannot safely serve several inference requests at once」——
     * Windows ROCm 后端并发推理会直接崩，所以同一时刻只许一个请求在飞。
     *
     * <p><b>必须 static</b>：适配器是每次合成现造的，实例字段每次都是新锁，谁也拦不住谁。
     * 按 type 分锁而不是一把大锁：{@code gsv} 与 {@code gpt-sovits} 通常指向不同的
     * 服务实例，互相排队没有意义。
     */
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    /**
     * 已加载的权重组合：key = type，value = {@code gptModel + "\0" + sovitsModel}。
     *
     * <p>这是对上游 {@code OnceCell} 的<b>修正</b>，不是照抄：设置页可以改模型路径，
     * 「改完必须重启后端」不符合本项目「保存即生效」的语义。
     * 失败不写这张表，下一次合成会重新尝试。
     */
    private static final Map<String, String> LOADED = new ConcurrentHashMap<>();

    /** {@code \0} 分隔：路径里不会出现这个字符，拼起来不会把两组路径混成同一个 key。 */
    private static final String KEY_SEPARATOR = "\u0000";

    /** 上游 {@code gsv.rs:178} 的重试间隔（{@code sleep(Duration::from_millis(500))}）。 */
    private static final long RETRY_DELAY_MILLIS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TtsSettings settings;

    /** 构造时传入的引擎类型，决定读哪段配置、用哪把锁。 */
    private final String type;

    public GsvAdapter(TtsSettings settings, String type) {
        this.settings = settings;
        this.type = type;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public byte[] generate(String text, String emo) {
        // 第一件事就取锁：模型加载也要在锁里，否则两个请求会同时去 set 权重
        ReentrantLock lock = LOCKS.computeIfAbsent(type, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, Object> cfg = settings.engineConfig(type);

            String apiUrl = TtsParams.urlWithPort(
                    TtsParams.str(cfg, "apiUrl", ""), TtsParams.intVal(cfg, "port", 0));
            if (apiUrl.isEmpty()) {
                throw TtsException.notConfigured(type + " 配置缺少 apiUrl（GPT-SoVITS 服务地址）");
            }

            String refAudioPath = TtsParams.str(cfg, "referenceAudio", "");
            if (refAudioPath.isEmpty()) {
                // GPT-SoVITS 是零样本复刻：没有参考音频就没有音色，请求必被服务端拒绝
                throw TtsException.notConfigured(type + " 配置缺少 referenceAudio（参考音频路径）");
            }

            ensureModelLoaded(apiUrl, cfg);

            // prompt_lang 与 text_lang 合并成同一个 language 字段是刻意的（上游是两个独立配置项）：
            // 前端这两个引擎的表单只有一个「语种」下拉，没有第二个语言字段可用。
            // 合并后的语义是「参考音频的语言 = 合成语言」，多数场景成立；分离语言需要前端先加字段。
            String language = TtsParams.str(cfg, "language", "zh");

            ObjectNode body = JSON.createObjectNode();
            body.put("ref_audio_path", refAudioPath);
            body.put("prompt_text", TtsParams.str(cfg, "referenceText", ""));
            body.put("prompt_lang", language);
            body.put("text_lang", language);
            body.put("media_type", mediaType());
            body.put("speed_factor", 1.0);
            body.put("text_split_method", "cut0");
            body.put("top_k", 15);
            body.put("top_p", 1.0);
            body.put("temperature", 1.0);
            body.put("parallel_infer", true);
            body.put("text", text);

            // emo 被忽略：上游的六分类参考语音（gsv.rs:17-34 的 GSV_EMO_CATEGORIES）需要
            // 「分类 → 音频路径 + 文本 + 语言」三件套，而前端这两个引擎只有单组
            // referenceAudio / referenceText，没有配置来源可查。硬造一张空映射表只会得到
            // 永远走不到的死代码——等设置页补上六分类字段再接。这属于已知欠账，报告里单列。
            return postWithRetry(apiUrl + "/tts", body.toString());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 路径变了就重载权重（上游是 {@code OnceCell}，只加载一次）。
     *
     * <p>两个路径任一为空就整段跳过、不报错：上游 {@code gsv.rs:84-91} 同样只在
     * 「gpt 与 sovits 都给了」时才 set，缺一个说明用户想用服务端现有的权重。
     */
    private void ensureModelLoaded(String apiUrl, Map<String, Object> cfg) {
        String gptModel = TtsParams.str(cfg, "gptModel", "");
        String sovitsModel = TtsParams.str(cfg, "sovitsModel", "");
        if (gptModel.isEmpty() || sovitsModel.isEmpty()) {
            return;
        }

        String key = gptModel + KEY_SEPARATOR + sovitsModel;
        if (key.equals(LOADED.get(type))) {
            return;
        }

        // 两个端点成功时都可能回空体，必须用 getAllowEmpty；用 get 会把成功当失败
        TtsHttp.getAllowEmpty(apiUrl + "/set_gpt_weights",
                TtsParams.query("weights_path", gptModel), settings.getTtsTimeout());
        TtsHttp.getAllowEmpty(apiUrl + "/set_sovits_weights",
                TtsParams.query("weights_path", sovitsModel), settings.getTtsTimeout());
        // 两步都成功才记账：中途失败时下次重新从头 set（set_gpt 幂等，重做无副作用）
        LOADED.put(type, key);
    }

    /**
     * 发 {@code /tts}，网络层失败重试一次（上游 {@code gsv.rs:165-186}）。
     *
     * <p>为什么要重试：GPT-SoVITS 常被放在本机/局域网的冷门端口上，首个请求偶尔会因为
     * 服务刚被唤醒而连接超时，隔半秒再发就能成——对用户来说「多等半秒出声」远好过
     * 「这句没声音」。
     *
     * <p>非 2xx 也会落到 {@link TtsErrorKind#REQUEST_FAILED}（{@link TtsHttp} 不区分
     * 「连不上」与「服务端 5xx」），所以 5xx 同样会多试一次。上游只在传输错误时重试，
     * 这里多出的这一次对幂等的 {@code /tts} 没有副作用，且能让「后端刚重启」自愈。
     */
    private byte[] postWithRetry(String url, String jsonBody) {
        TtsException first;
        try {
            return TtsHttp.post(url, null, null, jsonBody, settings.getTtsTimeout());
        } catch (TtsException e) {
            if (!isRetryable(e)) {
                throw e;
            }
            first = e;
        }

        sleepBeforeRetry();
        try {
            return TtsHttp.post(url, null, null, jsonBody, settings.getTtsTimeout());
        } catch (TtsException second) {
            throw TtsException.requestFailed("GPT-SoVITS 请求重试后仍失败：" + second.getMessage()
                    + "；首次错误：" + first.getMessage());
        }
    }

    /** 配置错/响应格式错重试没有意义：同样的请求再发一次只会得到同样的结论。 */
    private static boolean isRetryable(TtsException e) {
        return e.kind() == TtsErrorKind.REQUEST_FAILED || e.kind() == TtsErrorKind.TIMEOUT;
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw TtsException.requestFailed("GPT-SoVITS 重试前被中断");
        }
    }

    /** 全局 {@code ttsAudioFormat}；空值回落 wav（与上游 {@code audio_format: "wav"} 一致）。 */
    private String mediaType() {
        String format = settings.getTtsAudioFormat();
        return format == null || format.isBlank() ? "wav" : format.trim();
    }
}
