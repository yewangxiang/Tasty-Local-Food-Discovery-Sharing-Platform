package com.example.community.service.impl;

import com.example.community.entity.ArticleEsDoc;
import com.example.community.entity.ArticleExposurePool;
import com.example.community.mapper.ArticleExposurePoolMapper;
import com.example.community.utils.DashScopeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleIndexAsyncService {

    private final DashScopeClient dashScopeClient;
    private final EsArticleService esArticleService;
    private final ArticleExposurePoolMapper exposurePoolMapper;

    @Async("recommendTaskExecutor")
    public void indexArticle(Long articleId, String text, String imageUrl) {
        try {
            float[] textVec = dashScopeClient.embedText(text);
            if (textVec == null) {
                return;
            }

            ArticleEsDoc doc = new ArticleEsDoc();
            doc.setId(String.valueOf(articleId));
            doc.setArticleId(articleId);
            doc.setTextVector(DashScopeClient.toDoubleList(textVec));
            esArticleService.upsert(doc);

            ArticleExposurePool pool = new ArticleExposurePool();
            pool.setArticleId(articleId);
            pool.setEnteredAt(LocalDateTime.now());
            pool.setEarlyCtr(0f);
            pool.setExpiresAt(LocalDateTime.now().plusHours(48));
            exposurePoolMapper.insert(pool);
        } catch (Exception e) {
            log.warn("indexArticle failed articleId={}: {}", articleId, e.getMessage());
        }
    }
}
