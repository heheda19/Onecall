package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.HybridSearchService.HybridSearchResult;
import org.example.service.VectorSearchService.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库智能检索 Agent
 * 包含：语义推测 (Query Rewrite) -> [向量召回 | BM25关键词检索 | 混合检索] -> RRF融合 -> 大模型重排序过滤
 *
 * 检索模式（通过 rag.search.mode 配置）：
 * - vector: 纯稠密向量检索（默认）
 * - keyword: 纯 BM25 关键词检索
 * - hybrid:  混合检索（向量 + BM25 + RRF 融合，推荐）
 */
@Service
public class KnowledgeAgentService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeAgentService.class);

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private VectorEmbeddingService vectorEmbeddingService;

    @Autowired(required = false)
    private HybridSearchService hybridSearchService;

    @Autowired(required = false)
    private KeywordSearchService keywordSearchService;

    @Value("${rag.search.mode:vector}")
    private String searchMode;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行智能检索工作流
     *
     * 检索流程（根据配置的 searchMode）:
     *   1. Query Rewrite（语义推测）
     *   2. 检索：vector/keyword/hybrid
     *   3. LLM Rerank 精排过滤
     */
    public List<SearchResult> intelligentSearch(String query) {
        return intelligentSearch(query, null);
    }

    // ============================================
    // 扩展: 意图路由 + KB 分区 - 透传partition (2026-05-30)
    // 新增 partition 参数，透传至底层检索服务
    // ============================================
    public List<SearchResult> intelligentSearch(String query, String partition) {
        // 查询改写已提升至 ChatService.executeChat() 中执行, 此处直接使用改写后的查询
        logger.info("知识库Agent - 查询: [{}], 检索模式: {}, partition: {}",
                query, searchMode, partition);

        // 检索（根据模式选择）
        int initialTopK = 10;
        List<SearchResult> rawResults;

        switch (searchMode.toLowerCase()) {
            case "keyword":
                rawResults = keywordSearch(query, initialTopK);
                break;
            case "hybrid":
                rawResults = hybridSearch(query, initialTopK, initialTopK, partition);
                break;
            case "vector":
            default:
                rawResults = vectorSearchService.searchSimilarDocuments(query, initialTopK, partition);
                break;
        }

        logger.info("知识库Agent - {} 检索返回 {} 条结果", searchMode, rawResults.size());

        if (rawResults.isEmpty()) {
            return rawResults;
        }

        // 第三步：LLM 精排与过滤 (Rerank)
        List<SearchResult> rerankedResults = rerankAndFilter(query, rawResults);
        logger.info("知识库Agent - Rerank 过滤: 从 {} 条保留了 {} 条高相关文档",
                rawResults.size(), rerankedResults.size());

        return rerankedResults;
    }

    /**
     * 纯 BM25 关键词检索
     */
    private List<SearchResult> keywordSearch(String query, int topK) {
        if (keywordSearchService == null) {
            logger.warn("KeywordSearchService 未就绪，降级为向量检索");
            return vectorSearchService.searchSimilarDocuments(query, topK);
        }
        List<KeywordSearchService.Bm25Result> bm25Results = keywordSearchService.search(query, topK);
        return bm25Results.stream().map(r -> {
            SearchResult sr = new SearchResult();
            sr.setId(r.getDocId());
            sr.setContent(r.getContent());
            sr.setScore((float) r.getBm25Score());
            return sr;
        }).collect(Collectors.toList());
    }

    /**
     * 混合检索（BM25 + 向量 + RRF 融合）
     */
    private List<SearchResult> hybridSearch(String query, int initialTopK, int finalTopK, String partition) {
        if (hybridSearchService == null) {
            logger.warn("HybridSearchService 未就绪，降级为向量检索");
            return vectorSearchService.searchSimilarDocuments(query, finalTopK, partition);
        }
        List<HybridSearchResult> hybridResults =
                hybridSearchService.hybridSearch(query, initialTopK, finalTopK, partition);
        return hybridResults.stream().map(r -> {
            SearchResult sr = new SearchResult();
            sr.setId(r.getDocId());
            sr.setContent(r.getContent());
            sr.setScore((float) r.getRrfScore());
            return sr;
        }).collect(Collectors.toList());
    }

    /**
     * 语义推测 Agent (2026-06-08 废弃: 已由 QueryRewriteService 取代)
     * @deprecated 使用 {@link QueryRewriteService#rewrite(String, java.util.List)} 替代,
     *             该服务支持对话历史、指代消解、上下文补全等增强能力
     */
    @Deprecated
    public String rewriteQuery(String query) {
        String prompt = String.format(
                "你是一个知识库检索意图分析专家。用户的查询可能比较模糊或口语化。\n" +
                        "请推测用户的真实意图，提取核心技术词汇，将其扩充并重写为一个适合检索的精准查询语句（包含同义词和专业术语）。\n" +
                        "只返回重写后的查询文本，不要任何解释。\n" +
                        "用户查询：%s", query
        );
        try {
            String rewrittenQuery = chatModel.call(prompt).trim();

            // 验证重写结果与原始查询的语义相似度
            try {
                List<Float> originalVector = vectorEmbeddingService.generateQueryVector(query);
                List<Float> rewrittenVector = vectorEmbeddingService.generateQueryVector(rewrittenQuery);

                float similarity = vectorEmbeddingService.calculateCosineSimilarity(originalVector, rewrittenVector);
                logger.info("知识库Agent - 查询重写语义相似度: {}", similarity);

                float threshold = 0.6f;
                if (similarity < threshold) {
                    logger.warn("知识库Agent - 重写结果语义漂移严重（相似度: {} < {}），使用原始查询", similarity, threshold);
                    return query;
                }
            } catch (Exception e) {
                logger.warn("语义相似度验证失败，使用重写查询: {}", e.getMessage());
            }

            return rewrittenQuery;
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
            return results.subList(0, Math.min(3, results.size()));
        }
    }
}
