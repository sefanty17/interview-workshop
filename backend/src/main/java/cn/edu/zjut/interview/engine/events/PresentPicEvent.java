package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.payload.PresentPicPayload;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全屏图片事件，对应 {@code script_engine/events/present_pic_event.rs}。
 *
 * <p>原版：{@code imagePath} 经 {@code resolve_script_media} 解析成剧本目录下的绝对路径，
 * 写进 {@code game_status.present_pic}（读档恢复用），再 emit {@code script:present-pic}
 * （{@code PresentPicPayload{imagePath, scale, duration?}}，{@code scale} 缺省 1.0）。
 *
 * <p>本项目没有「剧本目录 + 本地文件」这一层，图片由前端静态资源托管，因此路径原样下发
 * （解析失败时原版同样给空串，这里没有任何解析步骤）；也没有读档恢复，故不存状态。
 */
public class PresentPicEvent implements ScriptEvent {

    public static final String TYPE = "present_pic";

    private static final Logger log = LoggerFactory.getLogger(PresentPicEvent.class);

    @Override
    public String execute(EngineContext ctx) {
        JsonNode event = ctx.event();
        String imagePath = ctx.text("imagePath");
        double scale = event != null && event.path("scale").isNumber() ? event.get("scale").asDouble() : 1.0;

        ctx.sink().presentPic(new PresentPicPayload(imagePath, scale, ScriptEvent.parseDuration(event)));
        log.info("[PresentPicEvent] 展示图片: {}", imagePath);
        return null;
    }
}
