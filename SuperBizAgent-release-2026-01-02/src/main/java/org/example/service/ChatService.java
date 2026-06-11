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
import org.example.service.VectorSearchService.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
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

    @Autowired(required = false)
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    // ============================================
    // 扩展: 意图路由 + KB 分区 - FastRAG预检索模式 (2026-06-04)
    // 对标 FastRAG: 分类 -> 预检索 -> 注入文档 -> Agent 推理
    // 非知识库意图(confidence=none)直接跳过RAG, 避免浪费 LLM 调用
    // ============================================

    @Autowired
    private IntentClassifierService intentClassifier;

    @Autowired
    private KnowledgeAgentService knowledgeAgentService;

    @Autowired
    private QueryRewriteService queryRewriteService;

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
     * 创建标准对话 ChatModel
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史消息）
     * 2026-06-04 FastRAG预检索模式: 文档由应用层预检索并注入用户消息，
     * Agent 只需阅读文档 + 调用监控/日志工具，不再负责搜索知识库。
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("### 角色设定\n");
        promptBuilder.append("你是一个高级智能助手，具备强大的逻辑推理能力和工具调用能力，能够综合分析多方数据源来解决用户问题。\n\n");

        promptBuilder.append("### 工作方式\n");
        promptBuilder.append("用户消息中可能已包含预先检索到的知识库文档。");
        promptBuilder.append("如果存在，请优先基于这些文档回答问题。如果不存在或不足以覆盖用户问题，请调用工具补充。\n\n");

        promptBuilder.append("- 清点文件资产 (listUploadedFiles): 当用户询问有哪些文件、列出所有文档时调用。\n");
        promptBuilder.append("- 精确读取文件 (readSpecificFile): 当用户明确要求读取某个具体文件时调用。\n");
        promptBuilder.append("- 查询具体知识 (queryInternalDocs): 当预检索文档不足时作为补充搜索。\n");
        promptBuilder.append("- 时间查询 (getCurrentDateTime): 需要当前准确时间时调用。\n");
        promptBuilder.append("- 监控告警 (queryPrometheusAlerts): 排查系统故障时调用。\n");
        promptBuilder.append("- 云端日志 (腾讯云MCP服务): 排查报错、追踪请求时调用。\n\n");

        promptBuilder.append("### 回答约束\n");
        promptBuilder.append("1. 基于事实: 优先使用预检索文档中的内容。\n");
        promptBuilder.append("2. 拒绝幻觉: 未找到有效信息时诚实告知，严禁编造。\n");
        promptBuilder.append("3. 逻辑清晰: 使用 Markdown 格式排版回答。\n\n");

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
     */
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        List<ToolCallback> mergedTools = new ArrayList<>();
        if (getToolCallbacks() != null) {
            mergedTools.addAll(Arrays.asList(getToolCallbacks()));
        }
        for (Object toolObj : buildMethodToolsArray()) {
            mergedTools.addAll(Arrays.asList(ToolCallbacks.from(toolObj)));
        }

        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .tools(mergedTools.toArray(new ToolCallback[0]))
                .build();
    }

    // ============================================
    // FastRAG 预检索模式 (2026-06-04)
    // 1. LLM 意图分类 -> 确定分区 (confidence=none 则跳过RAG)
    // 2. 应用层调 RAG Pipeline (Query Rewrite -> 双路召回 -> RRF -> Rerank)
    // 3. 检索结果注入用户消息，Agent 只负责阅读+推理+调监控/日志工具
    // ============================================
    /**
     * 执行 ReactAgent 对话（非流式）
     * Pipeline (2026-06-08): 查询改写 -> 意图分类 -> 预检索 -> 注入 -> Agent
     */
    public String executeChat(ReactAgent agent, String question,
                              List<Map<String, String>> history) throws GraphRunnerException {
        // Step 1: 查询改写 (指代消解 + 上下文补全 + 口语转正式 + 关键词扩展)
        String rewritten = queryRewriteService.rewrite(question, history);

        // Step 2: 意图分类 (基于改写后的精准 query)
        IntentClassifierService.ClassificationResult classification =
            intentClassifier.classify(rewritten);

        if (classification.needsGuidance()) {
            logger.info("[RAG] 意图不明确，返回引导消息");
            return classification.guidanceMessage();
        }

        // 2026-06-04: 非知识库意图跳过RAG, 对标FastRAG
        // "现在几点了"、"你好" 这类不需要检索的查询直达Agent
        String enrichedQuestion;
        if ("none".equals(classification.confidence())) {
            logger.info("[RAG] 非KB意图(confidence=none)，跳过RAG，直达Agent");
            enrichedQuestion = rewritten;
        } else {
            logger.info("[RAG] 路由分区 -> {} | 预检索模式", classification.partition());

            try {
                List<SearchResult> docs =
                    knowledgeAgentService.intelligentSearch(rewritten, classification.partition());

                if (!docs.isEmpty()) {
                    logger.info("[RAG] 预检索完成 | 命中 {} 条文档 -> 注入Agent上下文", docs.size());
                    StringBuilder docBlock = new StringBuilder();
                    docBlock.append("### 已检索到的相关知识库文档 (分区: ")
                            .append(classification.partition()).append(")\n\n");
                    for (int i = 0; i < docs.size(); i++) {
                        docBlock.append("---\n**文档").append(i + 1).append("**:\n")
                                .append(docs.get(i).getContent()).append("\n\n");
                    }
                    docBlock.append("---\n**用户问题**: ").append(question);
                    enrichedQuestion = docBlock.toString();
                } else {
                    logger.info("[RAG] 预检索无结果，Agent自行补充搜索");
                    enrichedQuestion = question;
                }
            } catch (Exception e) {
                logger.warn("[RAG] 预检索异常，降级为纯Agent模式: {}", e.getMessage());
                enrichedQuestion = question;
            }
        }

        logger.info("执行 ReactAgent.call()");
        var response = agent.call(enrichedQuestion);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}
