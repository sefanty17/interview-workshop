package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsException;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 「SBV2 API」适配器，对应上游 {@code adapters/sbv2api.rs}。
 *
 * <p>协议：POST {@code /synthesize}，<b>JSON 包体</b>（与本地 SBV2 的 query 风格相反），
 * 键名照 {@code sbv2api.rs:39-46}：{@code ident / length_scale / sdp_ratio / speaker_id / style_id / text}。
 *
 * <p>两处与上游的差异：
 * <ul>
 *   <li>{@code modelName} 映射到 {@code ident}（上游字段名与前端叫法不同，语义都是「模型标识」）；</li>
 *   <li>{@code speed} 换算成 {@code length_scale = 1/speed}：上游把它硬编码成 1.0
 *       （{@code sbv2api.rs:30}），前端却有语速滑杆，理由同 {@link Sbv2Adapter}；
 *       默认 speed=1 时结果仍是 1.0。</li>
 * </ul>
 */
public class Sbv2ApiAdapter implements TtsAdapter {

    public static final String TYPE = "sbv2api";

    /** 上游 sbv2api.rs:31 的默认值：前端没有对应字段，保持上游的 0.0。 */
    private static final double DEFAULT_SDP_RATIO = 0.0;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TtsSettings settings;

    public Sbv2ApiAdapter(TtsSettings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public byte[] generate(String text, String emo) {
        Map<String, Object> cfg = settings.engineConfig(TYPE);
        String apiUrl = TtsParams.urlWithPort(TtsParams.str(cfg, "apiUrl", ""), 0);
        if (apiUrl.isEmpty()) {
            throw TtsException.notConfigured("sbv2api 配置缺少 apiUrl（SBV2 API 服务地址）");
        }

        double speed = TtsParams.dbl(cfg, "speed", 1.0);
        double lengthScale = speed > 0 ? 1.0 / speed : 1.0;

        ObjectNode body = JSON.createObjectNode();
        body.put("ident", TtsParams.str(cfg, "modelName", ""));
        body.put("length_scale", lengthScale);
        body.put("sdp_ratio", DEFAULT_SDP_RATIO);
        body.put("speaker_id", TtsParams.intVal(cfg, "speakerId", 0));
        body.put("style_id", TtsParams.intVal(cfg, "styleId", 0));
        body.put("text", text);

        return TtsHttp.post(apiUrl + "/synthesize", null, null, body.toString(), settings.getTtsTimeout());
    }
}
