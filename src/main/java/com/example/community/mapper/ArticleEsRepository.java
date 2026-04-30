package com.example.community.mapper;

import com.example.community.entity.ArticleEsDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ArticleEsRepository extends ElasticsearchRepository<ArticleEsDoc, String> {
}
