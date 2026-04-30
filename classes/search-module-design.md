# Craving 语义搜索模块代码设计

> 纯本地方案（nomic-embed + Qwen3.5 via Ollama），无云端 API 依赖。
> 搜索链路完全解耦：通过 ApplicationEvent 监听帖子发布，异步写入 ES，不侵入 ArticleService。

---

## 一、新增依赖（pom.xml）

```xml
<!-- Elasticsearch Java Client（含 Spring Data ES）-->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

> Spring Boot 3.3.3 管理的版本为 Spring Data ES 5.3.x，对应 ES Java Client 8.14.x，
> 支持 kNN + RRF 查询语法。无需额外指定版本号。

---

## 二、配置（application.yaml 追加）

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200  # ES 地址，生产环境替换为实际 IP

# Ollama 本地推理服务（运行在 Computer A，RTX 5060）
ollama:
  base-url: http://192.168.x.x:11434   # Computer A 的局域网 IP
  models:
    text-embed: nomic-embed-text:latest
    vision-embed: nomic-embed-vision:latest
    llm: qwen3.5:9b-q4_K_M             # 与 Ollama pull 时使用的 tag 保持一致
  food-classifier:
    threshold: 0.30    # cosine 相似度阈值，低于此值判定为非美食图片（需实验调整）
  query-expansion:
    enabled: true      # false 时跳过 Qwen 扩写，直接用原始 query

# ES 搜索参数
search:
  knn:
    k: 50              # kNN 返回的候选数（送入 RRF 融合）
    num-candidates: 100
  rrf:
    window-size: 100
    rank-constant: 60
  result-size: 10      # 最终返回给前端的条数
```

---

## 三、包结构

```
com.example.community.search
├── config
│   ├── ElasticsearchConfig.java     # ES 客户端 bean + RestClient（调用 Ollama）
│   └── AsyncConfig.java             # @Async 线程池配置
├── document
│   └── PostDocument.java            # ES 索引文档 @Document
├── dto
│   ├── SearchRequestDTO.java        # Controller 入参
│   └── SearchResultDTO.java         # Controller 出参
├── event
│   └── PostPublishedEvent.java      # 帖子发布事件（与 ArticleService 解耦用）
├── service
│   ├── OllamaEmbeddingService.java  # text / image embedding via Ollama HTTP
│   ├── FoodClassifierService.java   # 零样本美食分类（复用 nomic-embed-vision）
│   ├── QueryExpansionService.java   # Qwen query 改写扩展
│   ├── PostIndexService.java        # 监听 PostPublishedEvent，异步写 ES
│   └── PostSearchService.java       # 搜索编排（expand→embed→ES kNN/RRF）
└── controller
    └── SearchController.java        # GET/POST /api/search
```

---

## 四、各文件代码

---

### 4.1 `config/ElasticsearchConfig.java`

```java
package com.example.community.search.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 两个 Bean：
 * 1. ElasticsearchClient  —— Spring Data ES 自动配置，无需手动定义
 *    （spring.elasticsearch.uris 配置项生效即可）
 *
 * 2. ollamaRestClient  —— 专用于调用 Ollama HTTP API 的 RestClient，
 *    与 Spring 自带 RestClient 区分开，避免全局 baseUrl 污染。
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${ollama.base-url}")
    private String ollamaBaseUrl;

    /**
     * 专用于 Ollama 的 RestClient。
     * 调用方注入时使用 @Qualifier("ollamaRestClient")。
     */
    @Bean("ollamaRestClient")
    public RestClient ollamaRestClient() {
        return RestClient.builder()
                .baseUrl(ollamaBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
```

---

### 4.2 `config/AsyncConfig.java`

```java
package com.example.community.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 索引操作使用独立线程池，避免阻塞业务线程。
 * 队列满时使用 CallerRunsPolicy 降级（不丢任务，背压给调用方）。
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    @Bean("searchIndexExecutor")
    public Executor searchIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("search-index-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

---

### 4.3 `document/PostDocument.java`

```java
package com.example.community.search.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ES 索引文档。
 *
 * text_vector  : 768 维，由 nomic-embed-text 对 (title + content) 编码生成。
 * image_vector : 768 维，由 nomic-embed-vision 对封面图编码生成。
 *                无图帖子该字段缺失，kNN 搜索时自然不参与排名——这是预期行为。
 *
 * 两个向量均在 nomic 的对齐空间中，支持跨模态余弦相似度比较。
 *
 * createIndex = false：索引 Mapping 由手动脚本或首次运行时初始化，
 * 避免应用启动时自动重建覆盖已有数据。
 */
@Data
@Document(indexName = "posts", createIndex = false)
public class PostDocument {

    @Id
    private String id;          // 对应 MySQL article.id，存为字符串防止 JSON 精度丢失

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String content;     // 存摘要（summary）即可，不必存全文

    @Field(type = FieldType.Keyword)
    private String coverImageUrl;   // 封面图 URL，前端展示用

    @Field(type = FieldType.Keyword)
    private List<String> tags;

    @Field(type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Date)
    private LocalDateTime publishedAt;

    // ===== 向量字段 =====

    /**
     * 文本向量：对 "title + \n + summary" 编码。
     * dims = 768 对应 nomic-embed-text-v1.5 的输出维度。
     * index = true 启用 HNSW 近似最近邻索引。
     */
    @Field(type = FieldType.Dense_Vector, dims = 768)
    private float[] textVector;

    /**
     * 图片向量：对封面图编码。
     * 无图帖子该字段为 null，写入 ES 时省略该字段即可。
     */
    @Field(type = FieldType.Dense_Vector, dims = 768)
    private float[] imageVector;
}
```

> **ES 索引初始化脚本**（在 Kibana 或 curl 中执行一次）：
>
> ```json
> PUT /posts
> {
>   "mappings": {
>     "properties": {
>       "text_vector":  { "type": "dense_vector", "dims": 768, "index": true, "similarity": "cosine" },
>       "image_vector": { "type": "dense_vector", "dims": 768, "index": true, "similarity": "cosine" }
>     }
>   }
> }
> ```

---

### 4.4 `dto/SearchRequestDTO.java`

```java
package com.example.community.search.dto;

import lombok.Data;

/**
 * 搜索入参。三种合法组合：
 *   1. textQuery 非空，imageBase64 为 null  →  纯文本语义搜索（single kNN on text_vector）
 *   2. textQuery 为 null，imageBase64 非空  →  以图搜图（single kNN on image_vector）
 *   3. 两者都有                              →  图文联合搜索（RRF over two kNNs）
 */
@Data
public class SearchRequestDTO {

    /** 用户输入的文字 query，可为 null */
    private String textQuery;

    /**
     * 用户上传的查询图片，Base64 编码（不含 data:image/... 前缀）。
     * 前端负责压缩到合理尺寸（建议 512px 短边），避免传输过大。
     */
    private String imageBase64;

    /**
     * 图片 MIME 类型，配合 imageBase64 使用。
     * 例："image/jpeg" 或 "image/png"。
     */
    private String imageMimeType;
}
```

---

### 4.5 `dto/SearchResultDTO.java`

```java
package com.example.community.search.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SearchResultDTO {
    private List<PostHit> hits;
    private long totalHits;
    private String queryMode;   // "TEXT" | "IMAGE" | "HYBRID" — 前端 debug 用

    @Data
    @Builder
    public static class PostHit {
        private String postId;
        private String title;
        private String content;
        private String coverImageUrl;
        private List<String> tags;
        private double score;       // RRF 或 kNN 分数（用于排序，前端可不展示）
    }
}
```

---

### 4.6 `event/PostPublishedEvent.java`

```java
package com.example.community.search.event;

import com.example.community.entity.Article;
import org.springframework.context.ApplicationEvent;

/**
 * 帖子发布事件。
 * ArticleServiceImpl 在帖子状态变为"已发布"时发布此事件，
 * PostIndexService 监听并异步写入 ES。
 * 两个模块之间无直接依赖。
 */
public class PostPublishedEvent extends ApplicationEvent {

    private final Article article;

    public PostPublishedEvent(Object source, Article article) {
        super(source);
        this.article = article;
    }

    public Article getArticle() {
        return article;
    }
}
```

> **ArticleServiceImpl 中的触发点**（在现有发布逻辑末尾添加一行）：
>
> ```java
> // 注入 ApplicationEventPublisher publisher;
> publisher.publishEvent(new PostPublishedEvent(this, article));
> ```
>
> 这是唯一需要改动现有代码的地方。

---

### 4.7 `service/OllamaEmbeddingService.java`

```java
package com.example.community.search.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 封装对 Ollama /api/embed 端点的调用。
 *
 * Ollama embed API（v0.2+）格式：
 *   POST /api/embed
 *   { "model": "...", "input": "..." }
 *   Response: { "embeddings": [[0.1, 0.2, ...]] }
 *
 * 注意：nomic-embed-vision 的图片 embedding 传递方式：
 *   Ollama 目前（截至 2025 年）对图片 embedding 的官方格式尚在演化。
 *   本实现使用 data URI 格式传入 input 字段（"data:image/jpeg;base64,..."），
 *   这是 Ollama 对多模态 embed 的推荐方式，但请以实际 Ollama 版本文档为准。
 *   若无法工作，备用方案为使用 /api/embeddings 旧端点，格式相同。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaEmbeddingService {

    @Qualifier("ollamaRestClient")
    private final RestClient restClient;

    @Value("${ollama.models.text-embed}")
    private String textEmbedModel;

    @Value("${ollama.models.vision-embed}")
    private String visionEmbedModel;

    /**
     * 对文本生成 768 维 embedding。
     *
     * @param text 输入文本（建议不超过 512 token）
     * @return 归一化后的 float 数组，长度 768
     */
    public float[] embedText(String text) {
        return callEmbedApi(textEmbedModel, text);
    }

    /**
     * 对图片生成 768 维 embedding。
     *
     * @param base64Image Base64 编码的图片（不含 data URI 前缀）
     * @param mimeType    MIME 类型，如 "image/jpeg"
     * @return 归一化后的 float 数组，长度 768
     */
    public float[] embedImage(String base64Image, String mimeType) {
        // 构造 data URI，nomic-embed-vision 通过此格式识别图片输入
        String dataUri = "data:" + mimeType + ";base64," + base64Image;
        return callEmbedApi(visionEmbedModel, dataUri);
    }

    // ====== 内部实现 ======

    @SuppressWarnings("unchecked")
    private float[] callEmbedApi(String model, String input) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "input", input
        );

        Map<String, Object> response = restClient.post()
                .uri("/api/embed")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("embeddings")) {
            throw new RuntimeException("Ollama embed 响应格式异常，model=" + model);
        }

        // response["embeddings"] 是 List<List<Double>>，取第一条
        List<List<Double>> embeddings = (List<List<Double>>) response.get("embeddings");
        List<Double> vector = embeddings.get(0);

        float[] result = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            result[i] = vector.get(i).floatValue();
        }
        return result;
    }
}
```

---

### 4.8 `service/FoodClassifierService.java`

```java
package com.example.community.search.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 零样本美食图片分类器。
 *
 * 原理：复用已部署的 nomic-embed-vision（CLIP 对齐空间），
 *        无需额外训练或部署专门的分类模型。
 *
 * 步骤：
 *   1. 将图片编码为向量 V_img
 *   2. 将 FOOD_PROMPT 和 NON_FOOD_PROMPT 各编码为文本向量
 *   3. 计算 V_img 与两个 prompt 的余弦相似度
 *   4. 若 sim(food) > threshold，判定为美食图片
 *
 * 阈值（threshold）需要通过实验确定。建议收集 50 张美食 / 50 张非美食图片，
 * 用不同阈值测试，找到 precision/recall 的平衡点。初始值 0.30 仅为参考。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FoodClassifierService {

    private final OllamaEmbeddingService embeddingService;

    @Value("${ollama.food-classifier.threshold}")
    private float threshold;

    // 分类 prompt 对（使用英文，CLIP 系模型英文 prompt 效果更稳定）
    private static final String FOOD_PROMPT = "a photo of delicious food or a dish";
    private static final String NON_FOOD_PROMPT = "a photo that does not contain any food";

    // 缓存 prompt 向量（进程内复用，避免每次调用都重新计算）
    private volatile float[] foodPromptVector;
    private volatile float[] nonFoodPromptVector;

    /**
     * 判断图片是否为美食图片。
     *
     * @param base64Image Base64 编码图片
     * @param mimeType    MIME 类型
     * @return true 表示是美食图片，可以进入后续 embedding 和索引流程
     */
    public boolean isFood(String base64Image, String mimeType) {
        ensurePromptVectorsInitialized();

        float[] imageVector = embeddingService.embedImage(base64Image, mimeType);

        float simFood    = cosineSimilarity(imageVector, foodPromptVector);
        float simNonFood = cosineSimilarity(imageVector, nonFoodPromptVector);

        log.debug("Food classifier: sim_food={}, sim_non_food={}", simFood, simNonFood);

        // 双重判断：绝对阈值 + 相对比较（避免阈值设定不当时的误判）
        return simFood > threshold && simFood > simNonFood;
    }

    // ====== 内部方法 ======

    private void ensurePromptVectorsInitialized() {
        if (foodPromptVector == null) {
            synchronized (this) {
                if (foodPromptVector == null) {
                    foodPromptVector = embeddingService.embedText(FOOD_PROMPT);
                    nonFoodPromptVector = embeddingService.embedText(NON_FOOD_PROMPT);
                    log.info("Food classifier prompt vectors initialized.");
                }
            }
        }
    }

    /**
     * 余弦相似度。两个向量均应为 L2 归一化（nomic 模型默认输出已归一化）。
     * 归一化后余弦相似度 = 点积。
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不一致: " + a.length + " vs " + b.length);
        }
        float dot = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
```

---

### 4.9 `service/QueryExpansionService.java`

```java
package com.example.community.search.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 用 Qwen3.5 对用户的文字 query 进行语义扩展。
 *
 * 目的：覆盖同义词、相关词和上下位词，弥补单一 query 向量的语义盲区。
 * 例："麻辣" → "麻辣 川菜 火锅 辣椒 重口味 红油 花椒"
 *
 * 技术归类：这是 Query Expansion 的一种形式，与 HyDE（假设文档生成）的区别是：
 *   - HyDE：让 LLM 生成一篇假想的"完美答案文档"，用其向量做检索
 *   - Query Expansion：让 LLM 生成扩展关键词列表，拼接后编码
 * 两种方式均有效，本实现选 Query Expansion，因为输出更可控、token 消耗更少。
 *
 * 若 enabled=false 或 Qwen 调用失败，降级返回原始 query（不影响搜索可用性）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryExpansionService {

    @Qualifier("ollamaRestClient")
    private final RestClient restClient;

    @Value("${ollama.models.llm}")
    private String llmModel;

    @Value("${ollama.query-expansion.enabled}")
    private boolean enabled;

    private static final String EXPANSION_PROMPT_TEMPLATE =
            "你是一个美食搜索引擎的查询优化助手。\n" +
            "用户搜索词：%s\n" +
            "请输出 5~8 个与该搜索词相关的扩展词或短语（同义词、相关菜系、口味描述等），" +
            "用空格分隔，不要有序号、标点或解释，只输出词语本身。";

    /**
     * 扩展文字 query。
     *
     * @param originalQuery 用户原始输入
     * @return "原始 query + 扩展词" 的拼接字符串，用于后续 embedding
     */
    public String expand(String originalQuery) {
        if (!enabled || originalQuery == null || originalQuery.isBlank()) {
            return originalQuery;
        }

        try {
            String expansionWords = callQwen(originalQuery);
            String expanded = originalQuery + " " + expansionWords;
            log.debug("Query expansion: [{}] → [{}]", originalQuery, expanded);
            return expanded;
        } catch (Exception e) {
            // 降级：Qwen 调用失败不影响搜索
            log.warn("Query expansion failed, using original query. Error: {}", e.getMessage());
            return originalQuery;
        }
    }

    @SuppressWarnings("unchecked")
    private String callQwen(String query) {
        String prompt = String.format(EXPANSION_PROMPT_TEMPLATE, query);

        Map<String, Object> requestBody = Map.of(
                "model", llmModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", false,
                "options", Map.of(
                        "temperature", 0.3,    // 低温度，减少随机性
                        "num_predict", 64      // 扩展词不需要太多 token
                )
        );

        Map<String, Object> response = restClient.post()
                .uri("/api/chat")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new RuntimeException("Qwen 响应为空");
        }

        Map<String, Object> message = (Map<String, Object>) response.get("message");
        return ((String) message.get("content")).trim();
    }
}
```

---

### 4.10 `service/PostIndexService.java`

```java
package com.example.community.search.service;

import com.example.community.entity.Article;
import com.example.community.search.document.PostDocument;
import com.example.community.search.event.PostPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Base64;

/**
 * 帖子索引写入服务。
 *
 * 触发方式：监听 PostPublishedEvent（由 ArticleServiceImpl 发布），
 *           通过 @Async 在独立线程池执行，不阻塞业务接口响应。
 *
 * 写入流程：
 *   1. 图片前置检查（如有封面图，用 FoodClassifier 验证）
 *   2. 生成 text_vector
 *   3. 生成 image_vector（可选，无图或非美食图则跳过）
 *   4. 写入 ES
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostIndexService {

    private final ElasticsearchOperations esOperations;
    private final OllamaEmbeddingService embeddingService;
    private final FoodClassifierService foodClassifier;

    /**
     * 监听帖子发布事件并异步索引。
     * @Async 使用 AsyncConfig 中定义的 "searchIndexExecutor" 线程池。
     */
    @Async("searchIndexExecutor")
    @EventListener
    public void onPostPublished(PostPublishedEvent event) {
        Article article = event.getArticle();
        log.info("Indexing post to ES: id={}", article.getId());

        try {
            PostDocument doc = buildDocument(article);
            esOperations.save(doc);
            log.info("Post indexed successfully: id={}", article.getId());
        } catch (Exception e) {
            log.error("Failed to index post id={}: {}", article.getId(), e.getMessage(), e);
            // 索引失败不抛出异常，不影响业务链路
            // 生产环境可以接入重试队列（RabbitMQ）或告警
        }
    }

    private PostDocument buildDocument(Article article) {
        PostDocument doc = new PostDocument();
        doc.setId(String.valueOf(article.getId()));
        doc.setTitle(article.getTitle());
        doc.setContent(article.getSummary()); // 只存摘要，不存全文
        doc.setTags(article.getTags());
        doc.setUserId(article.getUserId());
        doc.setPublishedAt(article.getCreatedAt()); // BaseEntity 中的字段

        // ===== 文本向量（必须有）=====
        String textForEmbed = buildTextForEmbedding(article);
        float[] textVector = embeddingService.embedText(textForEmbed);
        doc.setTextVector(textVector);

        // ===== 图片向量（可选）=====
        if (!CollectionUtils.isEmpty(article.getImageUrls())) {
            String coverUrl = article.getImageUrls().get(0); // 取封面图（第一张）
            doc.setCoverImageUrl(coverUrl);
            tryBuildImageVector(doc, coverUrl);
        }

        return doc;
    }

    /**
     * 构建用于文本 embedding 的字符串。
     * 拼接标题 + 摘要 + 标签，给模型提供更完整的语义信息。
     */
    private String buildTextForEmbed(Article article) {
        StringBuilder sb = new StringBuilder();
        sb.append(article.getTitle());
        if (StringUtils.hasText(article.getSummary())) {
            sb.append("\n").append(article.getSummary());
        }
        if (!CollectionUtils.isEmpty(article.getTags())) {
            sb.append("\n").append(String.join(" ", article.getTags()));
        }
        return sb.toString();
    }

    // 为了在两处调用不重复，提取同名方法
    private String buildTextForEmbedding(Article article) {
        return buildTextForEmbed(article);
    }

    /**
     * 尝试下载封面图并生成 image_vector。
     * 失败时记录日志但不中断整体索引流程（text_vector 单独也能检索）。
     *
     * 注意：当前实现需要将图片 URL 转为 Base64。
     * 生产环境建议：图片上传时同步生成 Base64 并传入此处，避免回源下载。
     * 开发阶段 imageUrls 存的如果是本地路径，可直接读文件。
     */
    private void tryBuildImageVector(PostDocument doc, String imageUrl) {
        try {
            // 下载图片并转为 Base64
            byte[] imageBytes = downloadImage(imageUrl);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mimeType = guessMimeType(imageUrl);

            // 美食分类检查
            if (!foodClassifier.isFood(base64, mimeType)) {
                log.info("Post id={} cover image is not food, skipping image_vector.", doc.getId());
                return;
            }

            float[] imageVector = embeddingService.embedImage(base64, mimeType);
            doc.setImageVector(imageVector);

        } catch (Exception e) {
            log.warn("Failed to build image_vector for post id={}: {}", doc.getId(), e.getMessage());
        }
    }

    private byte[] downloadImage(String url) throws Exception {
        // 使用标准 HTTP 客户端下载，此处用 Java 11+ 内置的 HttpClient
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RuntimeException("图片下载失败，status=" + response.statusCode());
        }
        return response.body();
    }

    private String guessMimeType(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg"; // 默认 JPEG
    }
}
```

---

### 4.11 `service/PostSearchService.java`

```java
package com.example.community.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.community.search.document.PostDocument;
import com.example.community.search.dto.SearchRequestDTO;
import com.example.community.search.dto.SearchResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索编排服务。
 *
 * 查询路由逻辑（三种模式）：
 *   1. 纯文本（TEXT）   : query expansion → embed text → single kNN on text_vector
 *   2. 纯图片（IMAGE）  : food check → embed image → single kNN on image_vector
 *   3. 图文混合（HYBRID): 同时执行 1、2 → ES 原生 RRF 融合两路 kNN 结果
 *
 * 使用原生 ElasticsearchClient 而非 Spring Data ES 的 Repository，
 * 原因是 Spring Data ES 的高阶封装目前不直接支持 RRF rank 参数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostSearchService {

    private final ElasticsearchClient esClient;
    private final OllamaEmbeddingService embeddingService;
    private final QueryExpansionService queryExpansionService;
    private final FoodClassifierService foodClassifier;

    @Value("${search.knn.k}")
    private int knnK;

    @Value("${search.knn.num-candidates}")
    private int numCandidates;

    @Value("${search.rrf.window-size}")
    private int rrfWindowSize;

    @Value("${search.rrf.rank-constant}")
    private int rrfRankConstant;

    @Value("${search.result-size}")
    private int resultSize;

    public SearchResultDTO search(SearchRequestDTO request) {
        boolean hasText  = StringUtils.hasText(request.getTextQuery());
        boolean hasImage = StringUtils.hasText(request.getImageBase64());

        if (!hasText && !hasImage) {
            throw new IllegalArgumentException("textQuery 和 imageBase64 不能同时为空");
        }

        if (hasText && hasImage) {
            return searchHybrid(request);
        } else if (hasText) {
            return searchByText(request.getTextQuery());
        } else {
            return searchByImage(request.getImageBase64(), request.getImageMimeType());
        }
    }

    // ========== 三种搜索模式 ==========

    /**
     * 纯文本搜索：先 query expansion，再 embed，再 single kNN。
     */
    private SearchResultDTO searchByText(String query) {
        String expandedQuery = queryExpansionService.expand(query);
        float[] textVector = embeddingService.embedText(expandedQuery);

        List<KnnSearch> knns = List.of(buildKnn("text_vector", textVector));
        return executeSearch(knns, false, "TEXT");
    }

    /**
     * 纯图片搜索：食物检查 → embed → single kNN on image_vector。
     */
    private SearchResultDTO searchByImage(String base64Image, String mimeType) {
        if (!foodClassifier.isFood(base64Image, mimeType)) {
            log.info("Search image is not food, returning empty result.");
            return SearchResultDTO.builder()
                    .hits(List.of())
                    .totalHits(0)
                    .queryMode("IMAGE_REJECTED")
                    .build();
        }

        float[] imageVector = embeddingService.embedImage(base64Image, mimeType);
        List<KnnSearch> knns = List.of(buildKnn("image_vector", imageVector));
        return executeSearch(knns, false, "IMAGE");
    }

    /**
     * 图文混合搜索：两路 kNN + ES 原生 RRF 融合。
     * 关键点：text-only 帖子没有 image_vector，不出现在图片 kNN 结果中，
     *         但仍可通过文字 kNN 路径被召回——RRF 处理了这种不对称性。
     */
    private SearchResultDTO searchHybrid(SearchRequestDTO request) {
        // 食物检查
        if (!foodClassifier.isFood(request.getImageBase64(), request.getImageMimeType())) {
            // 图片非美食，降级为纯文本搜索
            log.info("Hybrid search: image not food, fallback to text-only.");
            return searchByText(request.getTextQuery());
        }

        String expandedQuery = queryExpansionService.expand(request.getTextQuery());
        float[] textVector  = embeddingService.embedText(expandedQuery);
        float[] imageVector = embeddingService.embedImage(
                request.getImageBase64(), request.getImageMimeType());

        List<KnnSearch> knns = List.of(
                buildKnn("text_vector",  textVector),
                buildKnn("image_vector", imageVector)
        );
        return executeSearch(knns, true, "HYBRID");
    }

    // ========== ES 查询构建 ==========

    private KnnSearch buildKnn(String field, float[] vector) {
        // float[] → List<Float>（ES Java Client kNN 接受 List<Float>）
        List<Float> queryVector = new ArrayList<>(vector.length);
        for (float v : vector) queryVector.add(v);

        return KnnSearch.of(k -> k
                .field(field)
                .queryVector(queryVector)
                .k(knnK)
                .numCandidates(numCandidates)
        );
    }

    private SearchResultDTO executeSearch(List<KnnSearch> knns, boolean useRrf, String mode) {
        try {
            SearchResponse<PostDocument> response = esClient.search(s -> {
                        s.index("posts")
                         .knn(knns)
                         .size(resultSize)
                         // 排除大字段，减少网络传输
                         .source(src -> src.filter(f -> f.excludes("text_vector", "image_vector")));

                        // RRF 只在多路 kNN 时启用
                        if (useRrf) {
                            s.rank(r -> r.rrf(rrf -> rrf
                                    .windowSize(rrfWindowSize)
                                    .rankConstant((long) rrfRankConstant)
                            ));
                        }
                        return s;
                    },
                    PostDocument.class
            );

            List<SearchResultDTO.PostHit> hits = response.hits().hits().stream()
                    .map(this::toPostHit)
                    .collect(Collectors.toList());

            long total = response.hits().total() != null
                    ? response.hits().total().value() : hits.size();

            return SearchResultDTO.builder()
                    .hits(hits)
                    .totalHits(total)
                    .queryMode(mode)
                    .build();

        } catch (Exception e) {
            log.error("ES search failed: {}", e.getMessage(), e);
            throw new RuntimeException("搜索服务暂时不可用", e);
        }
    }

    private SearchResultDTO.PostHit toPostHit(Hit<PostDocument> hit) {
        PostDocument doc = hit.source();
        return SearchResultDTO.PostHit.builder()
                .postId(hit.id())
                .title(doc != null ? doc.getTitle() : "")
                .content(doc != null ? doc.getContent() : "")
                .coverImageUrl(doc != null ? doc.getCoverImageUrl() : null)
                .tags(doc != null ? doc.getTags() : List.of())
                .score(hit.score() != null ? hit.score() : 0.0)
                .build();
    }
}
```

---

### 4.12 `controller/SearchController.java`

```java
package com.example.community.search.controller;

import com.example.community.common.Result;
import com.example.community.search.dto.SearchRequestDTO;
import com.example.community.search.dto.SearchResultDTO;
import com.example.community.search.service.PostSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;

/**
 * 搜索接口。
 *
 * 三个端点，对应三种搜索模式：
 *   GET  /api/search?q=...           纯文本搜索
 *   POST /api/search/image           纯图片搜索（上传图片文件）
 *   POST /api/search/hybrid          图文混合搜索
 *
 * 复用现有项目的 Result<T> 统一响应封装。
 * 图片在 Controller 层转为 Base64 后传入 Service，Service 不感知 HTTP。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final PostSearchService searchService;

    /**
     * 纯文本语义搜索。
     * 示例：GET /api/search?q=成都麻辣火锅
     */
    @GetMapping
    public Result<SearchResultDTO> searchByText(@RequestParam("q") String query) {
        SearchRequestDTO req = new SearchRequestDTO();
        req.setTextQuery(query);
        return Result.success(searchService.search(req));
    }

    /**
     * 以图搜图。
     * 前端通过 multipart/form-data 上传图片文件。
     */
    @PostMapping("/image")
    public Result<SearchResultDTO> searchByImage(
            @RequestParam("image") MultipartFile imageFile) throws Exception {
        SearchRequestDTO req = new SearchRequestDTO();
        req.setImageBase64(Base64.getEncoder().encodeToString(imageFile.getBytes()));
        req.setImageMimeType(imageFile.getContentType());
        return Result.success(searchService.search(req));
    }

    /**
     * 图文混合搜索。
     * 前端同时传文字 query 和图片。
     */
    @PostMapping("/hybrid")
    public Result<SearchResultDTO> searchHybrid(
            @RequestParam("q") String query,
            @RequestParam("image") MultipartFile imageFile) throws Exception {
        SearchRequestDTO req = new SearchRequestDTO();
        req.setTextQuery(query);
        req.setImageBase64(Base64.getEncoder().encodeToString(imageFile.getBytes()));
        req.setImageMimeType(imageFile.getContentType());
        return Result.success(searchService.search(req));
    }
}
```

---

## 五、数据流总结

```
【写入路径（帖子发布时）】
ArticleServiceImpl.publish()
    → publishEvent(PostPublishedEvent)
        → @Async PostIndexService.onPostPublished()
            → buildTextForEmbedding() → OllamaEmbeddingService.embedText()   → text_vector
            → downloadImage() → FoodClassifier.isFood() → (pass)
                             → OllamaEmbeddingService.embedImage() → image_vector
            → ElasticsearchOperations.save(PostDocument)

【读取路径（用户搜索时）】
SearchController
    → PostSearchService.search()
        ├── TEXT:   QueryExpansionService.expand() → embedText() → kNN(text_vector)
        ├── IMAGE:  FoodClassifier.isFood() → embedImage() → kNN(image_vector)
        └── HYBRID: 以上两路并行 → ES RRF rank
    → SearchResultDTO → Result<SearchResultDTO>
```

---

## 六、已知局限和待验证项

| 项目 | 说明 |
|------|------|
| nomic-embed-vision 图片 embedding 的 Ollama API 格式 | 需要用实际部署的 Ollama 版本验证 data URI 方式是否生效 |
| ES `@Document` 自动建索引 | `createIndex=false`，需手动运行第 4.3 节的 mapping 初始化脚本 |
| FoodClassifier 阈值 0.30 | 需要实测样本调整，建议记录每次判定结果方便回溯 |
| PostIndexService 图片下载 | 开发阶段可注释掉图片下载步骤，仅索引文本向量，避免依赖 CDN |
| Qwen 模型 tag 名称 | 需与 `ollama list` 输出的实际 tag 一致，填入 application.yaml |
```
