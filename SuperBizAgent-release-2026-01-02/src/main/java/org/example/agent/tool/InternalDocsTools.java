package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.FileUploadConfig;
import org.example.service.KnowledgeAgentService;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.io.IOException;

/**
 * 内部文档查询工具
 * 2026-06-04 简化: 移除 ThreadLocal 分区兜底逻辑。
 * 分区路由已由 ChatService 在 Agent 之外完成(FastRAG预检索模式)，
 * 此工具仅作为 Agent 的补充搜索手段，不再承担路由职责。
 */
@Component
public class InternalDocsTools {

    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);

    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";

    private final KnowledgeAgentService knowledgeAgentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public InternalDocsTools(KnowledgeAgentService knowledgeAgentService) {
        this.knowledgeAgentService = knowledgeAgentService;
    }

    @Autowired
    private FileUploadConfig fileUploadConfig;

    // ============================================
    // 2026-06-04 简化: 移除 ThreadLocal 兜底逻辑
    // Agent 可选的补充搜索工具, partition 参数保留但不强制
    // ============================================
    @Tool(description = "补充搜索工具。仅当预检索文档不足以回答问题时调用。" +
            "partition 可选: partition_cpu/memory/disk/service/slow/default")
    public String queryInternalDocs(
            @ToolParam(description = "搜索查询")
            String query,
            @ToolParam(description = "知识库分区ID(可选)，如 partition_cpu")
            String partition) {

        if (partition == null || partition.isBlank()) {
            partition = "partition_default";
        }

        logger.info("[Tool] queryInternalDocs | query={} | partition={}", query, partition);

        try {
            List<VectorSearchService.SearchResult> searchResults =
                    knowledgeAgentService.intelligentSearch(query, partition);

            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base for partition: " + partition + ".\"}";
            }

            return objectMapper.writeValueAsString(searchResults);

        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败 | partition={}", partition, e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", e.getMessage());
        }
    }

    @Tool(description = "核心工具：获取当前知识库中所有已上传的文件列表。当用户询问'有哪些文件'、'上传了什么'、'资料库包含什么文档'时，必须优先调用此工具。")
    public String listUploadedFiles() {
        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();

            if (!Files.exists(uploadDir)) {
                return "当前知识库为空，尚未上传任何文件。";
            }

            List<String> fileNames = Files.list(uploadDir)
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md") || name.endsWith(".txt"))
                    .collect(Collectors.toList());

            if (fileNames.isEmpty()) {
                return "当前知识库为空，尚未找到支持检索的文档。";
            }

            return "【知识库文件清单】\n- " + String.join("\n- ", fileNames);

        } catch (IOException e) {
            return "扫描本地知识库目录失败: " + e.getMessage();
        }
    }

    @Tool(description = "精确文件读取工具：当用户明确要求读取、查看、总结某个具体文件（如 '命令.txt'）的完整内容时，必须调用此工具。传入完整文件名即可获取原始文本内容。")
    public String readSpecificFile(
            @ToolParam(description = "要读取的完整文件名，例如：命令.txt")
            String fileName) {
        try {
            Path fileDir = Paths.get("uploads");
            Path filePath = fileDir.resolve(fileName).normalize();

            if (!filePath.startsWith(fileDir.normalize())) {
                return "❌ 非法路径，无法访问。";
            }

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return "❌ 文件 [" + fileName + "] 不存在。";
            }

            String content = Files.readString(filePath, StandardCharsets.UTF_8);

            if (content.isBlank()) {
                return "⚠️ 文件 [" + fileName + "] 存在，但内容为空。";
            }

            return "【文件 " + fileName + " 内容】\n" + content;

        } catch (Exception e) {
            return "❌ 读取文件失败：" + e.getMessage();
        }
    }
}
