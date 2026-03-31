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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.io.IOException;

import java.util.List;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
//    private final VectorSearchService vectorSearchService;
    
//    @Value("${rag.top-k:3}")
//    private int topK = 3; // 默认值

    // 修改为注入知识库 Agent 服务
    private final KnowledgeAgentService knowledgeAgentService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
//    /**
//     * 构造函数注入依赖
//     * Spring 会自动注入 VectorSearchService
//     */
//    @Autowired
//    public InternalDocsTools(VectorSearchService vectorSearchService) {
//        this.vectorSearchService = vectorSearchService;
//    }

    @Autowired
    public InternalDocsTools(KnowledgeAgentService knowledgeAgentService) {
        this.knowledgeAgentService = knowledgeAgentService;
    }

    @Autowired
    private FileUploadConfig fileUploadConfig;
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
//    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
//            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
//            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
//            "stored in the company's documentation.")
//    public String queryInternalDocs(
//            @ToolParam(description = "Search query describing what information you are looking for")
//            String query) {
//
//
//        try {
//            // 使用向量搜索服务检索相关文档
//            List<VectorSearchService.SearchResult> searchResults =
//                    vectorSearchService.searchSimilarDocuments(query, topK);
//
//            if (searchResults.isEmpty()) {
//                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
//            }
//
//            // 将搜索结果转换为 JSON 格式
//            String resultJson = objectMapper.writeValueAsString(searchResults);
//
//
//            return resultJson;
//
//        } catch (Exception e) {
//            logger.error("[工具错误] queryInternalDocs 执行失败", e);
//            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
//                    e.getMessage());
//        }
//    }

    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information...")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for")
            String query) {

        try {
            // 【核心修改点】不再直接查 Milvus，而是交给知识库 Agent 进行 意图重写 -> 召回 -> 重排序过滤
            List<VectorSearchService.SearchResult> searchResults =
                    knowledgeAgentService.intelligentSearch(query);

            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            return objectMapper.writeValueAsString(searchResults);

        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", e.getMessage());
        }
    }




    @Tool(description = "核心工具：获取当前知识库中所有已上传的文件列表。当用户询问“有哪些文件”、“上传了什么”、“资料库包含什么文档”时，必须优先调用此工具。")
    public String listUploadedFiles() {
        try {
            // 这里的 "uploads" 替换为你 FileUploadController 中实际保存文件的本地目录路径
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();

            if (!Files.exists(uploadDir)) {
                return "当前知识库为空，尚未上传任何文件。";
            }

            // 使用 Java NIO 高效遍历目录下的常规文件
            List<String> fileNames = Files.list(uploadDir)
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md") || name.endsWith(".txt")) // 可选：只列出特定后缀的文件
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
            // 文件目录（你可以改成自己的目录）
            Path fileDir = Paths.get("uploads");
            Path filePath = fileDir.resolve(fileName).normalize();

            // 安全防护：只允许访问 uploads 内的文件
            if (!filePath.startsWith(fileDir.normalize())) {
                return "❌ 非法路径，无法访问。";
            }

            // 文件是否存在
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return "❌ 文件 [" + fileName + "] 不存在。";
            }

            // 读取内容
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
