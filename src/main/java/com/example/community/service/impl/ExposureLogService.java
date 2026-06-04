package com.example.community.service.impl;

import com.example.community.entity.UserBehavior;
import com.example.community.mapper.UserBehaviorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExposureLogService {

    private final UserBehaviorMapper behaviorMapper;

    @Async("recommendTaskExecutor")
    public void logExposure(Long userId, List<Long> articleIds) {
        for (Long articleId : articleIds) {
            try {
                UserBehavior behavior = new UserBehavior();
                behavior.setUserId(userId);
                behavior.setArticleId(articleId);
                behavior.setEventType(UserBehavior.EXPOSE);
                behavior.setCreatedAt(LocalDateTime.now());
                behaviorMapper.insert(behavior);
            } catch (Exception e) {
                log.warn("logExposure failed userId={} articleId={}: {}",
                        userId, articleId, e.getMessage());
            }
        }
    }
}
