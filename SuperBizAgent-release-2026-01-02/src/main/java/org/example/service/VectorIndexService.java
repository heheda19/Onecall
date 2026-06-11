package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.config.IntentNodesConfig;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired(required = false)
    private KeywordSearchService keywordSearchService;

    // ============================================
    // 扩展: 意图路由 + KB 分区 - 上传自动归类 (2026-05-30)
    // 文件上传时根据文件名关键词匹配 partition，
    // 如 "cpu_high_usage.md" → partition_cpu
    // ============================================
    @Autowired
    private IntentNodesConfig intentNodesConfig;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
                    
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     * 
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);

        // 1. 读取文件内容
        String content = Files.readString(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());

        // 2. 删除该文件的旧数据（如果存在）
        deleteExistingData(path.toString());

        // 3. 文档分片
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        // 4. 为每个分片生成向量并插入 Milvus + 同步 BM25 索引
        String normalizedSource = path.toString().replace(File.separator, "/");

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);

            try {
                // 生成向量
                List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());

                // 构建元数据（包含文件信息）
                Map<String, Object> metadata = buildMetadata(path.toString(), chunk, chunks.size());

                // 生成文档 ID（与 Milvus 中保持一致，便于 RRF 融合去重）
                String chunkId = UUID.nameUUIDFromBytes(
                        (normalizedSource + "_" + chunk.getChunkIndex()).getBytes()
                ).toString();

                // 插入到 Milvus
                insertToMilvus(chunk.getContent(), vector, metadata, chunk.getChunkIndex());

                // 同步到 BM25 关键词索引
                if (keywordSearchService != null) {
                    try {
                        keywordSearchService.addDocument(chunkId, chunk.getContent(), normalizedSource);
                        logger.info("BM25 索引已同步: chunkId={}", chunkId);
                    } catch (Exception e) {
                        logger.warn("BM25 索引同步失败（不影响向量索引）: {}", e.getMessage());
                    }
                }

                logger.info("✓ 分片 {}/{} 索引成功", i + 1, chunks.size());

            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 索引失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    /**
     * 删除文件的旧数据（根据 metadata._source）
     */
    private void deleteExistingData(String filePath) {
        // 同步移除 BM25 索引
        if (keywordSearchService != null) {
            try {
                Path bm25Path = Paths.get(filePath).normalize();
                String normalizedSource = bm25Path.toString().replace(File.separator, "/");
                keywordSearchService.removeBySource(normalizedSource);
                logger.debug("BM25 索引已移除来源: {}", normalizedSource);
            } catch (Exception e) {
                logger.warn("移除 BM25 索引失败: {}", e.getMessage());
            }
        }

        try {
            // 使用统一的路径分隔符（正斜杠）用于Milvus存储，避免表达式解析错误
            // 将系统路径转换为统一格式
            Path path = Paths.get(filePath).normalize();
            String normalizedPath = path.toString().replace(File.separator, "/");
            
            // 构建删除表达式：metadata["_source"] == "xxx"
            String expr = String.format("metadata[\"_source\"] == \"%s\"", normalizedPath);
            
            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            // 确保 collection 已加载（删除操作需要集合已加载）
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            // 状态码 65535 表示集合已经加载，这不是错误
            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.warn("加载 collection 失败: {}", loadResponse.getMessage());
                return;
            }

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);

            if (response.getStatus() != 0) {
                logger.warn("删除旧数据时出现警告: {}", response.getMessage());
            } else {
                long deletedCount = response.getData().getDeleteCnt();
                logger.info("✓ 已删除文件的旧数据: {}, 删除记录数: {}", normalizedPath, deletedCount);
            }

        } catch (Exception e) {
            logger.warn("删除旧数据失败（可能是首次索引）: {}", e.getMessage());
        }
    }

    /**
     * 构建元数据（包含文件信息）
     */
    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();
        
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separator, "/");
        
        // 文件信息
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        
        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        
        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        
        return metadata;
    }

    /**
     * 插入向量到 Milvus
     */
    private void insertToMilvus(String content, List<Float> vector, 
                                Map<String, Object> metadata, int chunkIndex) throws Exception {
        try {
            // 确保 collection 已加载
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
            }

            // 生成唯一 ID（使用 _source + 分片索引）
            String source = (String) metadata.get("_source");
            String fileName = (String) metadata.get("_file_name");
            // ── 新增: 根据文件名推断 partition ──
            String partition = intentNodesConfig.resolvePartitionByFilename(fileName);
            metadata.put("_partition", partition);
            logger.debug("==> [分区归类] 文件={} → partition={}", fileName, partition);
            String id = UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes()).toString();

            // 构建字段数据
            List<InsertParam.Field> fields = new ArrayList<>();
            
            // ID 字段
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            
            // content 字段
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            
            // vector 字段
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            
            // metadata 字段（JSON 对象）
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            // 构建插入参数
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withPartitionName(partition)
                    .withFields(fields)
                    .build();

            // 执行插入
            R<MutationResult> insertResponse = milvusClient.insert(insertParam);

            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
            }

            logger.debug("向量插入成功: id={}, source={}, partition={}, chunk={}", id, source, partition, chunkIndex);

        } catch (Exception e) {
            logger.error("插入向量到 Milvus 失败", e);
            throw e;
        }
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
