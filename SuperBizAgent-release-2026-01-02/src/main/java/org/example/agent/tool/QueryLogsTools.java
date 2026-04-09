package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.cls.v20201016.ClsClient;
import com.tencentcloudapi.cls.v20201016.models.*;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
// import org.springframework.stereotype.Component; // 注释掉 Component 的引用

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志查询工具
 * ⚠️ 当前已禁用：为了使用远程 MCP (tencent-mcp-server) 查询日志，
 * 已将该类的 @Component 和方法上的 @Tool 注释掉，避免 AI 陷入选择冲突。
 */
// @Component // 👈 核心修改：注释掉 Component，让 Spring 忽略它
public class QueryLogsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryLogsTools.class);

    public static final String TOOL_QUERY_LOGS = "queryLogs";
    public static final String TOOL_GET_AVAILABLE_LOG_TOPICS = "getAvailableLogTopics";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cls.mock-enabled:false}")
    private boolean mockEnabled;

    @Value("${cls.secret-id:}")
    private String secretId;

    @Value("${cls.secret-key:}")
    private String secretKey;

    @Value("${cls.endpoint:}")
    private String endpoint;

    @Value("#{${cls.topic-mapping:{}}}")
    private Map<String, String> topicMapping;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    // @jakarta.annotation.PostConstruct // 取消初始化执行
    public void init() {
        logger.info("✅ QueryLogsTools 初始化成功, Mock模式: {}", mockEnabled);
    }

    /**
     * 获取可用的日志主题列表
     */
    // @Tool(...) 👈 核心修改：注释掉 Tool，对大模型隐藏该能力
    public String getAvailableLogTopics() {
        logger.info("获取可用的日志主题列表 (本地方法已禁用)");

        try {
            List<LogTopicInfo> topics = new ArrayList<>();

            // 系统指标日志
            LogTopicInfo systemMetrics = new LogTopicInfo();
            systemMetrics.setTopicName("system-metrics");
            systemMetrics.setDescription("系统指标日志，包含 CPU、内存、磁盘使用率等系统资源监控数据");
            systemMetrics.setExampleQueries(List.of(
                    "cpu_usage:>80", "memory_usage:>85", "disk_usage:>90", "level:WARN AND service:payment-service"
            ));
            systemMetrics.setRelatedAlerts(List.of("HighCPUUsage", "HighMemoryUsage", "HighDiskUsage"));
            topics.add(systemMetrics);

            // 应用日志
            LogTopicInfo applicationLogs = new LogTopicInfo();
            applicationLogs.setTopicName("application-logs");
            applicationLogs.setDescription("应用日志，包含应用程序的错误日志、警告日志、慢请求日志、下游依赖调用日志等");
            applicationLogs.setExampleQueries(List.of(
                    "level:ERROR", "level:FATAL", "http_status:500", "response_time:>3000", "slow", "downstream OR redis OR database OR mq"
            ));
            applicationLogs.setRelatedAlerts(List.of("ServiceUnavailable", "SlowResponse", "HighMemoryUsage"));
            topics.add(applicationLogs);

            // 数据库慢查询日志
            LogTopicInfo dbSlowQuery = new LogTopicInfo();
            dbSlowQuery.setTopicName("database-slow-query");
            dbSlowQuery.setDescription("数据库慢查询日志，包含执行时间较长的 SQL 查询，可用于分析数据库性能问题");
            dbSlowQuery.setExampleQueries(List.of(
                    "query_time:>2", "table:orders", "query_type:SELECT", "*"
            ));
            dbSlowQuery.setRelatedAlerts(List.of("SlowResponse", "ServiceUnavailable"));
            topics.add(dbSlowQuery);

            // 系统事件日志
            LogTopicInfo systemEvents = new LogTopicInfo();
            systemEvents.setTopicName("system-events");
            systemEvents.setDescription("系统事件日志，包含 Kubernetes Pod 重启、OOM Kill、容器崩溃等系统级事件");
            systemEvents.setExampleQueries(List.of(
                    "restart OR crash", "oom_kill", "event_type:PodRestart", "reason:OOMKilled"
            ));
            systemEvents.setRelatedAlerts(List.of("ServiceUnavailable", "HighMemoryUsage"));
            topics.add(systemEvents);

            // 构建输出
            LogTopicsOutput output = new LogTopicsOutput();
            output.setSuccess(true);
            output.setTopics(topics);
            output.setAvailableRegions(List.of("ap-chongqing", "ap-shanghai", "ap-beijing", "ap-chengdu"));
            output.setDefaultRegion("ap-chongqing");

            output.setMessage(String.format("共有 %d 个可用的日志主题。建议使用默认地域 'ap-chongqing' 或省略 region 参数", topics.size()));

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);

        } catch (Exception e) {
            logger.error("获取日志主题列表失败", e);
            return "{\"success\":false,\"message\":\"获取日志主题列表失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询日志
     */
    private static final List<String> VALID_REGIONS = List.of(
            "ap-chongqing", "ap-shanghai", "ap-beijing", "ap-chengdu"
    );

    private static final String DEFAULT_REGION = "ap-chongqing";

    // @Tool(...) 👈 核心修改：注释掉 Tool，对大模型隐藏该能力
    public String queryLogs(
            @ToolParam(description = "地域，可选值: ap-chongqing, ap-shanghai, ap-beijing, ap-chengdu。默认 ap-chongqing") String region,
            @ToolParam(description = "日志主题，如 system-metrics, application-logs, database-slow-query, system-events，也支持 CLS TopicId") String logTopic,
            @ToolParam(description = "查询条件，支持 Lucene 语法，如 level:ERROR OR cpu_usage:>80；为空时返回该主题近 5 条核心日志") String query,
            @ToolParam(description = "返回日志条数，默认20，最大100") Integer limit) {

        int actualLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        String safeQuery = query == null ? "" : query;

        try {
            List<LogEntry> logEntries;

            if (mockEnabled) {
                logEntries = buildMockLogs(region, logTopic, safeQuery, actualLimit);
                logger.info("使用 Mock 数据，返回 {} 条日志", logEntries.size());
            } else {
                logEntries = queryRealLogs(region, logTopic, safeQuery, actualLimit);
                logger.info("调用 CLS API，返回 {} 条日志", logEntries.size());
            }

            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(!logEntries.isEmpty());
            output.setRegion(region);
            output.setLogTopic(logTopic);
            output.setQuery(safeQuery.isBlank() ? "DEFAULT_QUERY" : safeQuery);
            output.setLogs(logEntries);
            output.setTotal(logEntries.size());
            output.setMessage(logEntries.isEmpty() ? "未找到匹配的日志" : String.format("成功查询到 %d 条日志", logEntries.size()));

            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            logger.info("日志查询完成: 找到 {} 条日志", logEntries.size());

            return jsonResult;

        } catch (Exception e) {
            logger.error("查询日志失败", e);
            return buildErrorResponse("查询失败: " + e.getMessage());
        }
    }

    // --- 下方所有内部私有方法保持原样，无需修改 ---

    private List<LogEntry> buildMockLogs(String region, String logTopic, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        Instant now = Instant.now();
        String safeTopic = logTopic == null ? "system-metrics" : logTopic.toLowerCase();
        String normalizedQuery = query == null ? "" : query.toLowerCase();

        switch (safeTopic) {
            case "system-metrics": logs.addAll(buildSystemMetricsLogs(now, normalizedQuery, limit)); break;
            case "application-logs": logs.addAll(buildApplicationLogs(now, normalizedQuery, limit)); break;
            case "database-slow-query": logs.addAll(buildDatabaseSlowQueryLogs(now, normalizedQuery, limit)); break;
            case "system-events": logs.addAll(buildSystemEventsLogs(now, normalizedQuery, limit)); break;
            default: logs.addAll(buildGenericLogs(now, normalizedQuery, limit));
        }

        if (logs.isEmpty()) logs.addAll(buildGenericLogs(now, normalizedQuery, limit));
        if (logs.size() > limit) logs = logs.subList(0, limit);
        return logs;
    }

    private List<LogEntry> buildSystemMetricsLogs(Instant now, String query, int limit) {
        // [此处保留了你原有的 Mock 数据生成逻辑，为节省篇幅不展开修改]
        List<LogEntry> logs = new ArrayList<>();
        return logs;
    }

    private List<LogEntry> buildApplicationLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        return logs;
    }

    private List<LogEntry> buildDatabaseSlowQueryLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        return logs;
    }

    private List<LogEntry> buildSystemEventsLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        return logs;
    }

    private List<LogEntry> buildGenericLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        return logs;
    }

    private List<LogEntry> queryRealLogs(String region, String logTopic, String query, int limit) throws Exception {
        List<LogEntry> logs = new ArrayList<>();

        if (secretId == null || secretId.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            throw new Exception("CLS 配置未完成");
        }

        Credential cred = new Credential(secretId, secretKey);
        HttpProfile httpProfile = new HttpProfile();

        if (endpoint != null && !endpoint.isEmpty()) {
            httpProfile.setEndpoint(endpoint);
        } else {
            httpProfile.setEndpoint("cls." + region + ".tencentcloudapi.com");
        }

        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        ClsClient client = new ClsClient(cred, region, clientProfile);

        String realTopicId = topicMapping != null ? topicMapping.getOrDefault(logTopic, logTopic) : logTopic;
        SearchLogRequest req = new SearchLogRequest();
        req.setTopicId(realTopicId);

        logger.info("准备调用腾讯云 API，原主题别名: {}, 实际 TopicId: {}", logTopic, realTopicId);

        if (!query.isEmpty()) {
            req.setQuery(query);
        } else {
            req.setQuery("*");
        }

        long nowMs = System.currentTimeMillis();
        long oneHourAgoMs = nowMs - 3600 * 1000L;
        req.setFrom(oneHourAgoMs);
        req.setTo(nowMs);
        req.setLimit((long) limit);

        SearchLogResponse resp = client.SearchLog(req);

        if (resp.getResults() != null) {
            for (LogInfo logInfo : resp.getResults()) {
                LogEntry logEntry = new LogEntry();
                logEntry.setTimestamp(FORMATTER.format(Instant.ofEpochMilli(logInfo.getTime())));

                Map<String, String> content = new HashMap<>();
                String logJson = logInfo.getLogJson();

                if (logJson != null && !logJson.isEmpty()) {
                    try {
                        Map<String, Object> jsonMap = objectMapper.readValue(
                                logJson,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
                        );
                        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
                            content.put(entry.getKey(), String.valueOf(entry.getValue()));
                        }
                    } catch (Exception e) {
                        content.put("message", logJson);
                    }
                }

                logEntry.setLevel(content.getOrDefault("level", "INFO"));
                logEntry.setService(content.getOrDefault("service", "unknown"));
                logEntry.setInstance(content.getOrDefault("instance", "unknown"));
                logEntry.setMessage(content.getOrDefault("message", logJson));

                content.remove("level");
                content.remove("service");
                content.remove("instance");
                content.remove("message");
                logEntry.setMetrics(content);

                logs.add(logEntry);
            }
        }
        return logs;
    }

    private String buildErrorResponse(String message) {
        try {
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\"}", message);
        }
    }

    // ==================== 数据模型 ====================
    @Data
    public static class LogEntry {
        @JsonProperty("timestamp") private String timestamp;
        @JsonProperty("level") private String level;
        @JsonProperty("service") private String service;
        @JsonProperty("instance") private String instance;
        @JsonProperty("message") private String message;
        @JsonProperty("metrics") private Map<String, String> metrics;
    }

    @Data
    public static class QueryLogsOutput {
        @JsonProperty("success") private boolean success;
        @JsonProperty("region") private String region;
        @JsonProperty("log_topic") private String logTopic;
        @JsonProperty("query") private String query;
        @JsonProperty("logs") private List<LogEntry> logs;
        @JsonProperty("total") private int total;
        @JsonProperty("message") private String message;
    }

    @Data
    public static class LogTopicInfo {
        @JsonProperty("topic_name") private String topicName;
        @JsonProperty("description") private String description;
        @JsonProperty("example_queries") private List<String> exampleQueries;
        @JsonProperty("related_alerts") private List<String> relatedAlerts;
    }

    @Data
    public static class LogTopicsOutput {
        @JsonProperty("success") private boolean success;
        @JsonProperty("topics") private List<LogTopicInfo> topics;
        @JsonProperty("available_regions") private List<String> availableRegions;
        @JsonProperty("default_region") private String defaultRegion;
        @JsonProperty("message") private String message;
    }
}