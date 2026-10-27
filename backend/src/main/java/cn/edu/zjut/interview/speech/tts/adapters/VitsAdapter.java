package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsException;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;

import java.util.Map;

/**
 * Simple-Vits-API 的 VITS 适配器，对应上游 {@code adapters/vits.rs}。
 *
 * <p>协议：<b>GET</b> {@code /voice/vits}，参数全在查询串里——注意它和同族的 bv2 不一样
 * （bv2 是 POST + JSON），这是上游 {@code vits.rs:42} 的实际写法，不是笔误；
 * 因此本适配器发出的请求方法也是 GET。
 *
 * <p>与上游的两处差异：
 * <ul>
 *   <li>地址用 {@link TtsParams#urlWithPort} 拼：前端把 host 与 port 拆成两个输入框
 *       （{@code apiUrl=http://127.0.0.1} + {@code port=23456}），上游是单个 api_url；</li>
 *   <li>{@code noiseScale} / {@code lengthScale} <b>本批不上送</b>：上游请求里根本没有这两个
 *       参数（{@code vits.rs:35-40} 只有 id/format/lang/text），凭空加参数等于自造协议，
 *       目标服务是否认这两个名字无从验证。等有真实服务可测再补，见报告「拿不准的点」。</li>
 * </ul>
 */
public class VitsAdapter implements TtsAdapter {

    public static final String TYPE = "vits";

    private final TtsSettings settings;

    public VitsAdapter(TtsSettings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public byte[] generate(String text, String emo) {
        Map<String, Object> cfg = settings.engineConfig(TYPE);
        String apiUrl = TtsParams.urlWithPort(TtsParams.str(cfg, "apiUrl", ""), TtsParams.intVal(cfg, "port", 0));
        if (apiUrl.isEmpty()) {
            throw TtsException.notConfigured("vits 配置缺少 apiUrl（Simple-Vits-API 地址）");
        }

        Map<String, String> query = TtsParams.query(
                "id", String.valueOf(TtsParams.intVal(cfg, "speakerId", 0)),
                "format", Bv2Adapter.audioFormat(settings.getTtsAudioFormat()),
                "lang", TtsParams.str(cfg, "language", "zh"),
                "text", text);

        return TtsHttp.get(apiUrl + "/voice/vits", query, settings.getTtsTimeout());
    }
}
