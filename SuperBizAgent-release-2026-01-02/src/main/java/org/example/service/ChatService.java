package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder promptBuilder = new StringBuilder();

        // 1. 角色设定 (Persona)
        promptBuilder.append("### 角色设定\n");
        promptBuilder.append("你是一个高级智能助手，具备强大的逻辑推理能力和工具调用能力，能够综合分析多方数据源来解决用户问题。\n\n");

        // 2. 核心能力与工具调用指南 (Tool Guidelines)
        promptBuilder.append("### 工具使用指南\n");
        promptBuilder.append("你可以通过调用外部工具来获取实时数据或专有信息。请严格评估用户的意图，并遵循以下触发条件：\n");

        // 【核心优化】拓宽知识库的触发边界，明确告知模型这里包含了用户的上传文件
        promptBuilder.append("你拥有三个操作知识库的专属工具，请严格按照以下边界条件选择调用：\n");

        // 1. 查目录（宏观视角）
        promptBuilder.append("- **清点文件资产 (listUploadedFiles)**：当用户询问“知识库里有什么文件”、“列出所有文档”、“我刚才传了什么”时，必须且只能调用此工具，获取当前磁盘上的真实文件列表。\n");

        // 2. 读全文（微观/精确视角）-> 这是为你新加的工具准备的
        promptBuilder.append("- **精确读取文件 (readSpecificFile)**：当用户明确说出了具体的【文件名】（含后缀），并要求“读取”、“查看原文”、“输出全文”或“总结这个文件”时（例如：“读取 命令.txt”、“把 slow_response.md 发给我”），**必须**调用此工具获取原始全文。严禁使用 queryInternalDocs 去模糊检索具体文件。\n");

        // 3. 查知识（语义/模糊视角）
        promptBuilder.append("- **查询具体知识 (queryInternalDocs)**：当用户提出具体的业务问题、排查报错、询问流程，且**没有**指名道姓要求读取某个特定文件时（例如：“怎么排查磁盘高占用？”、“系统 OOM 怎么办？”），调用此工具在多文档中进行语义检索。\n");

        promptBuilder.append("- **时间查询 (getCurrentDateTime)**：当需要知道当前准确的日期、时间或处理相对时间（如“今天”、“刚才”）时调用。\n");
        promptBuilder.append("- **监控告警 (queryPrometheusAlerts)**：当排查系统故障、询问应用健康状况或 Prometheus 告警状态时调用。\n");
        promptBuilder.append("- **云端日志 (腾讯云MCP服务)**：当排查具体报错、追踪请求或查询腾讯云 CLS 日志时调用（默认地域: ap-guangzhou，默认时间范围: 近一个月）。\n\n");

        // 3. 行为与输出约束 (Constraints)
        promptBuilder.append("### 回答约束\n");
        promptBuilder.append("1. **基于事实**：回答必须以工具检索到的内容为准。如果检索结果包含多个文件片段，请进行归纳提炼。\n");
        promptBuilder.append("2. **拒绝幻觉**：如果调用工具后未返回有效结果，请诚实告知用户“在知识库/系统中未找到相关记录”，严禁编造虚假的文件名或数据。\n");
        promptBuilder.append("3. **逻辑清晰**：请使用 Markdown 格式（如加粗、列表、代码块）来排版你的回答，使其清晰易读。\n\n");

        // 4. 对话历史 (History Integration)
        if (history != null && !history.isEmpty()) {
            promptBuilder.append("### 历史上下文\n");
            for (Map<String, String> msg : history) {
                String role = "user".equals(msg.get("role")) ? "用户" : "助手";
                String content = msg.get("content");
                promptBuilder.append("**").append(role).append("**: ").append(content).append("\n");
            }
            promptBuilder.append("\n");
        }

        promptBuilder.append("请结合上述要求与历史上下文，思考并回答用户的最新问题。");

        return promptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}
