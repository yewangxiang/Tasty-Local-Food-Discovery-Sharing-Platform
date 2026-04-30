-- 推荐系统基础表
-- 执行前确保已连接到 community_db

-- 行为事件表
CREATE TABLE IF NOT EXISTS user_behavior (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT NOT NULL,
    article_id  BIGINT NOT NULL,
    event_type  TINYINT NOT NULL COMMENT '1曝光 2点击 3点赞 4评论 5收藏 6跳过',
    dwell_time  INT    DEFAULT NULL COMMENT '停留秒数，event_type=2时有效',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (user_id, created_at),
    INDEX idx_article   (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 新帖曝光池（冷启动）
CREATE TABLE IF NOT EXISTS article_exposure_pool (
    article_id  BIGINT PRIMARY KEY,
    entered_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    early_ctr   FLOAT    DEFAULT 0 COMMENT '早期点击率',
    expires_at  DATETIME NOT NULL,
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
