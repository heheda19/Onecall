package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    /**
     * 搜索相似文档（全量检索，不指定分区）
     *
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        return searchSimilarDocuments(query, topK, null);
    }

    // ============================================
    // 扩展: 意图路由 + KB 分区 - Milvus Partition检索 (2026-05-30)
    // 新增 partition 参数，使用 Milvus withPartitionNames() 剪枝搜索范围
    // ============================================
    /**
     * 搜索相似文档（支持分区检索）
     *
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @param partition 分区名称，为null或空时全量检索
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK, String partition) {
        try {
            // ── 新增: Partition 过滤 ──
            if (partition != null && !partition.isEmpty()) {
                logger.info("[RAG] ① 查询向量化 | query={} | top_k={} | partition={}", query, topK, partition);
            } else {
                logger.info("[RAG] ① 全量检索(未指定分区) | query={} | top_k={}", query, topK);
            }

            // 1. 将查询文本向量化
            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            // 2. 构建搜索参数
            SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(topK)
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withParams("{\"nprobe\":10}");

            // ── 新增: Partition 过滤 ──
            if (partition != null && !partition.isEmpty()) {
                searchBuilder.withPartitionNames(Collections.singletonList(partition));
                logger.info("[RAG] ③ 分区检索 | partition={} | top_k={}", partition, topK);
            } else {
                logger.info("[RAG] ③ 全量检索(未指定分区) | top_k={}", topK);
            }

            SearchParam searchParam = searchBuilder.build();

            // 3. 执行搜索
            R<SearchResults> searchResponse = milvusClient.search(searchParam);

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
            }

            // 4. 解析搜索结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> results = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());

                // 解析 metadata
                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }

                results.add(result);
            }

            logger.info("[RAG] ④ 搜索完成 | 命中数={}", results.size());
            return results;

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;

    }
}
