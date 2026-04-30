package com.example.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.community.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文章Mapper接口
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 原子增加浏览量，避免并发覆盖
     */
    @Update("UPDATE article SET view_count = view_count + 1 WHERE id = #{id}")
    void incrementViewCount(@Param("id") Long id);

    /**
     * 更新点赞数
     */
    @Update("UPDATE article SET like_count = like_count + #{delta} WHERE id = #{id}")
    void updateLikeCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 查询用户所有已发布文章的总点赞数
     */
    @Select("SELECT COALESCE(SUM(like_count), 0) FROM article WHERE user_id = #{userId} AND status = 1 AND deleted = 0")
    Integer selectTotalLikesByUserId(@Param("userId") Long userId);
}
