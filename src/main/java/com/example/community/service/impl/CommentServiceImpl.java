package com.example.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.community.entity.Article;
import com.example.community.entity.Comment;
import com.example.community.entity.User;
import com.example.community.mapper.ArticleMapper;
import com.example.community.mapper.CommentMapper;
import com.example.community.service.CommentService;
import com.example.community.service.UserService;
import com.example.community.vo.CommentVO;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评论Service实现类
 */
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment>
        implements CommentService {

    @Resource
    private UserService userService;

    @Resource
    private ArticleMapper articleMapper;

    /**
     * 添加评论
     */
    @Override
    public void addComment(Long articleId, String content, Long parentId) {
        // 验证评论内容
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("评论内容不能为空");
        }

        // 获取当前登录用户
        String username = (String) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();

        User user = userService.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));

        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 验证文章是否存在
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new RuntimeException("文章不存在");
        }

        // 验证父评论是否存在（如果parentId不为0）
        if (parentId != null && parentId != 0) {
            Comment parentComment = this.getById(parentId);
            if (parentComment == null) {
                throw new RuntimeException("父评论不存在");
            }
        }

        // 创建评论
        Comment comment = new Comment();
        comment.setArticleId(articleId);
        comment.setContent(content);
        comment.setUserId(user.getId());
        comment.setParentId(parentId == null ? 0L : parentId);

        this.save(comment);
    }

    /**
     * 获取文章的评论树
     */
    @Override
    public List<CommentVO> getTreeList(Long articleId) {
        // 1. 查出文章下所有评论，按创建时间升序
        List<Comment> allComments = this.list(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getArticleId, articleId)
                        .orderByAsc(Comment::getCreateTime)
        );

        if (allComments.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 批量获取用户信息（避免循环查库）
        List<Long> userIds = allComments.stream()
                .map(Comment::getUserId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, User> userMap = userService.listByIds(userIds)
                .stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // 3. 转为VO
        List<CommentVO> allVos = allComments.stream()
                .map(c -> {
                    CommentVO vo = new CommentVO();
                    BeanUtils.copyProperties(c, vo);

                    User u = userMap.get(c.getUserId());
                    if (u != null) {
                        vo.setUsername(u.getUsername());
                        vo.setUserAvatar(u.getAvatar());
                    }

                    return vo;
                })
                .collect(Collectors.toList());

        // 4. 构建树形结构（内存处理）
        // [修复] 原代码 vo.getParentId() == 0，其中 getParentId() 返回 Long（包装类型）
        // 与 int 字面量 0 比较时，Java 会自动拆箱（unboxing）为 long == 0，
        // 虽然通常不会空指针（因为我们总是赋值为0L），但这是不安全的写法，
        // 且 Long == Long 不能用 == 比较（超出 -128~127 缓存范围就是不同对象）。
        // 修复为 Long.valueOf(0L).equals(vo.getParentId()) 或 vo.getParentId() == 0L（unboxing安全）
        // 更清晰的写法：明确与 0L 的 equals 比较。
        List<CommentVO> rootNodes = allVos.stream()
                .filter(vo -> Long.valueOf(0L).equals(vo.getParentId()))
                .collect(Collectors.toList());

        // 递归找子节点
        for (CommentVO root : rootNodes) {
            findChildren(root, allVos);
        }

        return rootNodes;
    }

    /**
     * 递归查找子评论
     */
    private void findChildren(CommentVO parent, List<CommentVO> allList) {
        List<CommentVO> children = allList.stream()
                .filter(vo -> vo.getParentId().equals(parent.getId()))
                .collect(Collectors.toList());

        parent.setChildren(children);

        // 递归处理子节点
        for (CommentVO child : children) {
            findChildren(child, allList);
        }
    }
}
