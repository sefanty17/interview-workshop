package cn.edu.zjut.interview.agent;

/**
 * Agent 设置（前端可读写；沙箱目录为空表示默认 {@code data/}），对齐原版
 * {@code api/script_editor/agent.rs} 的 {@code AgentSettings}（camelCase）。
 */
public record AgentSettings(
        /** LLM provider ID；null 表示跟随聊天主 LLM。 */
        String providerId,
        /** 文件沙箱根目录；null 表示默认 {@code data/}。 */
        String sandboxDir,
        /** 命令是否自动审批（无需用户确认）。 */
        boolean autoApproveCommands,
        /** 是否允许文件工具访问沙箱之外的任意路径。 */
        boolean allowAnyPath,
        /** 工具调用轮数上限；-1 表示无上限。 */
        int maxToolRounds,
        /** 自定义系统提示；null 使用内置默认提示。 */
        String systemPrompt,
        /** 思考模式覆盖；null 表示跟随 provider 默认（独立于主对话 LLM 设置）。 */
        Boolean enableThinking) {

    /** {@link SkillAgentConfig} → 前端设置视图。 */
    public static AgentSettings from(SkillAgentConfig config) {
        return new AgentSettings(config.getProviderId(), config.getSandboxDir(),
                config.isAutoApproveCommands(), config.isAllowAnyPath(), config.getMaxToolRounds(),
                config.getSystemPrompt(), config.getEnableThinking());
    }

    /** 前端设置 → {@link SkillAgentConfig}（写入前先做的归一化在这里）。 */
    public SkillAgentConfig toConfig() {
        SkillAgentConfig config = new SkillAgentConfig();
        config.setProviderId(blankToNull(providerId));
        config.setSandboxDir(blankToNull(sandboxDir));
        config.setAutoApproveCommands(autoApproveCommands);
        config.setAllowAnyPath(allowAnyPath);
        config.setMaxToolRounds(maxToolRounds);
        config.setSystemPrompt(blankToNull(systemPrompt));
        config.setEnableThinking(enableThinking);
        return config;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
