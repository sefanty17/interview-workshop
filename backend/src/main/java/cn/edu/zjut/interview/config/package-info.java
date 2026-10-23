/**
 * 设置项注册表与校验（对应原版 {@code config/tree.rs} / {@code api/settings.rs}）。
 *
 * <h2>配置键 → 消费点清单</h2>
 *
 * <p>规矩：每个注册的键必须满足下面三条之一，否则 {@code ConfigServiceTest}
 * 的守卫用例会失败（防止再出现「能改能存但没人读」的假开关）。
 *
 * <ol>
 *   <li>在 {@link cn.edu.zjut.interview.config.ConfigService#CONSUMED_KEYS} 里（有真实消费点）；</li>
 *   <li>或 {@code ConfigSetting.extension() == true}（原版没有、本项目自建的键）；</li>
 *   <li>或 {@code description} 里写明「仅存值」。</li>
 * </ol>
 *
 * <h3>有真实消费点（7 项）</h3>
 *
 * <table border="1">
 *   <caption>消费点</caption>
 *   <tr><th>key</th><th>消费文件 / 位置</th></tr>
 *   <tr><td>{@code llm.model}</td>
 *       <td>{@code llm/LlmOptions.java} → {@code LlmClient.buildRequest} 请求体 {@code model}</td></tr>
 *   <tr><td>{@code llm.temperature}</td>
 *       <td>{@code llm/LlmOptions.java} → 请求体 {@code temperature}</td></tr>
 *   <tr><td>{@code llm.top_p}</td>
 *       <td>{@code llm/LlmOptions.java} → 请求体 {@code top_p}</td></tr>
 *   <tr><td>{@code llm.max_tokens}</td>
 *       <td>{@code llm/LlmOptions.java} → 请求体 {@code max_tokens}</td></tr>
 *   <tr><td>{@code llm.timeout_secs}</td>
 *       <td>{@code llm/LlmOptions.java} → {@code HttpRequest.timeout}</td></tr>
 *   <tr><td>{@code chat.reply_count}</td>
 *       <td>{@code engine/ChatTuning.java} → {@code prompt/PromptAssembler}（回复段数上限）</td></tr>
 *   <tr><td>{@code chat.merge_threshold}</td>
 *       <td>{@code engine/ChatTuning.java} → {@code stream/SentenceSplitter}（台词合并阈值）</td></tr>
 * </table>
 *
 * <h3>仅存值 / 本项目扩展（10 项）</h3>
 *
 * <ul>
 *   <li>{@code llm.chat_provider_id} — 仅存值：本项目只有单一 provider。</li>
 *   <li>{@code llm.enable_thinking} — 仅存值：后端暂不请求思考链。</li>
 *   <li>{@code features.enable_time_sense} — 仅存值：时间注入逻辑未迁。</li>
 *   <li>{@code features.enable_emotion_classifier} — 仅存值：别名表始终启用，无开关。</li>
 *   <li>{@code display.font_family} / {@code display.font_size} — 本项目扩展
 *       （原版字号属投屏模块 {@code cast.dialog_font_size}，本项目不迁投屏故自建）。</li>
 *   <li>{@code chat.merge_delay_ms} / {@code chat.text_speed} / {@code chat.auto_advance_ms} /
 *       {@code chat.inline_action} — 仅存值：消费端在前端（旧设置页读自己的 localStorage）。</li>
 * </ul>
 *
 * <p>已删除：{@code audio.volume_character}。原版没有任何 {@code AUDIO_*} 常量
 * （角色音量在前端 localStorage 里），后端注册它等于一个「能改能存但不生效」的假开关。
 */
package cn.edu.zjut.interview.config;
