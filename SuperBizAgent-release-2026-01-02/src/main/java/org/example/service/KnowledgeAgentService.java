package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.VectorSearchService.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库智能检索 Agent
 * 包含：语义推测 (Query Rewrite) -> 向量召回 -> 大模型重排序过滤 (LLM Rerank)
 */
@Service
public class KnowledgeAgentService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeAgentService.class);

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private VectorSearchService vectorSearchService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行智能检索工作流
     */
    public List<SearchResult> intelligentSearch(String originalQuery) {
        // 第一步：语义推测 (Query Rewrite)，优化模糊查询
        String rewrittenQuery = rewriteQuery(originalQuery);
        logger.info("知识库Agent - 原始查询: [{}], 重写后查询: [{}]", originalQuery, rewrittenQuery);

        // 第二步：扩大向量召回范围 (获取 Top 10)
        int initialTopK = 10;
        List<SearchResult> rawResults = vectorSearchService.searchSimilarDocuments(rewrittenQuery, initialTopK);

        if (rawResults.isEmpty()) {
            return rawResults;
        }

        // 第三步：LLM 精排与过滤 (Rerank)，降噪并降低后续 Token 消耗
        List<SearchResult> rerankedResults = rerankAndFilter(originalQuery, rawResults);
        logger.info("知识库Agent - Rerank 过滤: 从 {} 条保留了 {} 条高相关文档", rawResults.size(), rerankedResults.size());

        return rerankedResults;
    }

    /**
     * 1. 语义推测 Agent
     */
    private String rewriteQuery(String query) {
        String prompt = String.format(
                "你是一个知识库检索意图分析专家。用户的查询可能比较模糊或口语化。\n" +
                        "请推测用户的真实意图，提取核心技术词汇，将其扩充并重写为一个适合向量数据库检索的精准查询语句（包含同义词和专业术语）。\n" +
                        "只返回重写后的查询文本，不要任何解释。\n" +
                        "用户查询：%s", query
        );
        try {
            return chatModel.call(prompt).trim();
        } catch (Exception e) {
            logger.warn("Query Rewrite 失败，使用原查询: {}", e.getMessage());
            return query;
        }
    }

    /**
     * 3. 过滤与重排序 Agent
     */
    private List<SearchResult> rerankAndFilter(String query, List<SearchResult> results) {
        StringBuilder docsBuilder = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            docsBuilder.append(String.format("[%d] %s\n", i, results.get(i).getContent()));
        }

        String prompt = String.format(
                "你是一个文档相关性评估专家。请判断以下候选文档中，哪些与用户的原始查询真正相关，可以用来回答该问题。\n" +
                        "用户原始查询：%s\n\n" +
                        "候选文档列表：\n%s\n\n" +
                        "请仅返回相关文档的编号列表，格式必须为合法的 JSON 数组，例如 [0, 2, 3]。\n" +
                        "如果都不相关，请返回 []。除了 JSON 数组外不要输出任何其他内容（不要Markdown代码块标记）。",
                query, docsBuilder.toString()
        );

        try {
            String response = chatModel.call(prompt).trim();
            // 清理可能的 markdown 标记，确保是纯 JSON
            response = response.replaceAll("```json", "").replaceAll("```", "").trim();

            List<Integer> relevantIndices = objectMapper.readValue(response, new TypeReference<List<Integer>>() {});
            List<SearchResult> filteredResults = new ArrayList<>();

            for (Integer index : relevantIndices) {
                if (index >= 0 && index < results.size()) {
                    filteredResults.add(results.get(index));
                }
            }
            return filteredResults;
        } catch (Exception e) {
            logger.warn("Rerank 失败，退化为返回前 3 条结果: {}", e.getMessage());
            // 降级策略：如果大模型解析失败，安全地返回前3条
            return results.subList(0, Math.min(3, results.size()));
        }
    }
}