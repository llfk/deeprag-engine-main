package com.deeprag.store;

import com.deeprag.config.DeepRagConfig;
import com.deeprag.log.ConsoleLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.ListCollectionsResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.*;

/**
 * 基于 Milvus 的向量存储实现
 * <p>
 * 集合 Schema：
 * - chunk_id  (VarChar, 主键, 最大128)
 * - content   (VarChar, 最大65535)
 * - embedding (FloatVector, 维度由参数指定)
 * - metadata  (JSON)
 * <p>
 * 索引：AUTOINDEX + COSINE 度量
 */
public class MilvusVectorStore implements VectorStore {

    private final MilvusClientV2 client;
    private final String collectionPrefix;

    public MilvusVectorStore(DeepRagConfig.VectorStoreConfig config) {
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri("http://" + config.getHost() + ":" + config.getPort())
                .build();
        this.client = new MilvusClientV2(connectConfig);
        this.collectionPrefix = config.getCollectionPrefix();
        ConsoleLog.info("Milvus 向量存储已连接: " + config.getHost() + ":" + config.getPort());
    }

    @Override
    public void createCollection(String name, int dimension) {
        String fullName = collectionPrefix + name;

        // 如果集合已存在，先删除再重建（避免 schema 不一致导致 upsert 失败）
        try {
            Boolean exists = client.hasCollection(HasCollectionReq.builder()
                    .collectionName(fullName)
                    .build());
            if (exists != null && exists) {
                ConsoleLog.step("集合已存在，删除重建: " + fullName);
                client.dropCollection(DropCollectionReq.builder()
                        .collectionName(fullName)
                        .build());
            }
        } catch (Exception e) {
            ConsoleLog.warn("检查集合存在性失败，将尝试创建: " + e.getMessage());
        }

        // 定义集合 Schema
        CreateCollectionReq.CollectionSchema schema = client.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_id")
                .dataType(DataType.VarChar)
                .maxLength(128)
                .isPrimaryKey(true)
                .autoID(false)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content")
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding")
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("metadata")
                .dataType(DataType.JSON)
                .build());

        // 定义索引：AUTOINDEX + COSINE
        // 索引方案交给 Milvus 自动处理，并使用余弦相似度衡量向量之间的相似程度
        List<IndexParam> indexParams = List.of(
                IndexParam.builder()
                        .fieldName("embedding")
                        .indexType(IndexParam.IndexType.AUTOINDEX)
                        .metricType(IndexParam.MetricType.COSINE)
                        .build()
        );

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(fullName)
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build();
        client.createCollection(createReq);
        ConsoleLog.info("集合创建成功: " + fullName + " (维度=" + dimension + ")");
    }

    @Override
    public void upsert(String collection, List<ChunkEmbedding> data) {
        if (data == null || data.isEmpty()) {
            ConsoleLog.warn("upsert 数据为空，跳过");
            return;
        }

        String fullName = collectionPrefix + collection;

        // 构建 Gson JsonObject 行数据
        List<JsonObject> rows = new ArrayList<>();
        for (ChunkEmbedding ce : data) {
            JsonObject row = new JsonObject();
            row.addProperty("chunk_id", ce.getChunkId() != null ? ce.getChunkId() : "");
            row.addProperty("content", ce.getContent() != null ? ce.getContent() : "");
            row.add("embedding", toJsonArray(ce.getEmbedding()));
            row.add("metadata", toMetadataJson(ce.getMetadata()));
            rows.add(row);
        }

        InsertReq insertReq = InsertReq.builder()
                .collectionName(fullName)
                .data(rows)
                .build();
        client.insert(insertReq);
        ConsoleLog.step("Upsert 完成: " + fullName + " (数量=" + data.size() + ")");
    }

    @Override
    public List<SearchResult> searchDense(String collection, float[] queryVector, int topK, float threshold) {
        String fullName = collectionPrefix + collection;

        // 使用 FloatVec 封装查询向量（SDK 2.5+ 要求 BaseVector 类型）
        FloatVec floatVec = new FloatVec(toFloatList(queryVector));

        SearchReq searchReq = SearchReq.builder()
                .collectionName(fullName)
                .data(List.of(floatVec))
                .topK(topK)
                .outputFields(List.of("chunk_id", "content", "metadata"))
                .build();

        SearchResp resp = client.search(searchReq);
        List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);

        List<SearchResult> searchResults = new ArrayList<>();
        for (SearchResp.SearchResult r : results) {
            float score = r.getScore() != null ? r.getScore() : 0.0f;
            if (score < threshold) continue;

            // 从返回结果中提取字段
            Map<String, Object> entity = r.getEntity();
            String chunkId = entity != null && entity.get("chunk_id") != null
                    ? String.valueOf(entity.get("chunk_id")) : "";
            String content = entity != null && entity.get("content") != null
                    ? String.valueOf(entity.get("content")) : "";

            // 提取 metadata JSON（SDK 2.5.8 可能返回 Map 或 Gson JsonObject）
            Object metaObj = entity != null ? entity.get("metadata") : null;
            Map<String, Object> metadata = new HashMap<>();
            if (metaObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) metaObj;
                metadata = map;
            } else if (metaObj instanceof JsonObject jsonMeta) {
                for (String key : jsonMeta.keySet()) {
                    metadata.put(key, gsonValueToObject(jsonMeta.get(key)));
                }
            }

            searchResults.add(new SearchResult(chunkId, content, score, metadata));
        }
        ConsoleLog.step("向量检索完成: " + fullName + " (返回=" + searchResults.size() + ", topK=" + topK + ")");
        return searchResults;
    }

    @Override
    public void deleteBySource(String collection, String source) {
        String fullName = collectionPrefix + collection;

        // 通过 metadata 中的 source 字段过滤删除
        String filter = "metadata[\"source\"] == \"" + source + "\"";
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(fullName)
                .filter(filter)
                .build();
        client.delete(deleteReq);
        ConsoleLog.step("按来源删除完成: " + fullName + " (source=" + source + ")");
    }

    @Override
    public void dropCollection(String name) {
        String fullName = collectionPrefix + name;
        DropCollectionReq dropReq = DropCollectionReq.builder()
                .collectionName(fullName)
                .build();
        client.dropCollection(dropReq);
        ConsoleLog.info("集合已删除: " + fullName);
    }

    @Override
    public List<String> listCollections() {
        ListCollectionsResp resp = client.listCollections();
        List<String> result = new ArrayList<>();
        if (resp != null && resp.getCollectionNames() != null) {
            for (String c : resp.getCollectionNames()) {
                if (c.startsWith(collectionPrefix)) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    /**
     * 将 float[] 转换为 List<Float>（Milvus SDK 需要）
     */
    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }

    /**
     * 将 float[] 转换为 Gson JsonArray（用于 embedding 字段）
     */
    private JsonArray toJsonArray(float[] arr) {
        JsonArray array = new JsonArray(arr.length);
        for (float v : arr) array.add(v);
        return array;
    }

    /**
     * 将 Map<String, Object> 转换为 Gson JsonObject（用于 metadata JSON 字段）
     */
    private JsonObject toMetadataJson(Map<String, Object> meta) {
        JsonObject obj = new JsonObject();
        if (meta != null) {
            for (Map.Entry<String, Object> entry : meta.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof String) {
                    obj.addProperty(entry.getKey(), (String) val);
                } else if (val instanceof Number) {
                    obj.addProperty(entry.getKey(), (Number) val);
                } else if (val instanceof Boolean) {
                    obj.addProperty(entry.getKey(), (Boolean) val);
                } else if (val != null) {
                    obj.addProperty(entry.getKey(), String.valueOf(val));
                }
            }
        }
        return obj;
    }

    /**
     * 将 Gson JsonElement 转为 Java 原生对象（用于从搜索结果提取 metadata）
     */
    private Object gsonValueToObject(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsNumber();
            return p.getAsString();
        }
        if (el.isJsonArray()) return el.getAsJsonArray().toString();
        if (el.isJsonObject()) return el.getAsJsonObject().toString();
        return el.toString();
    }
}
