package com.example.community;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * 完整的测试套件
 * 按顺序执行所有测试类
 */
@Suite
@SuiteDisplayName("社区平台完整测试套件")
@SelectClasses({
    UserControllerTest.class,           // 1. 用户模块（注册、登录）
    UserProfileControllerTest.class,    // 2. 用户资料与设置模块
    ArticleControllerTest.class,        // 3. 文章模块（发布、列表、详情、点赞）
    ArticleExtendedTest.class,          // 4. 文章扩展功能（我的文章、草稿、搜索）
    CommentControllerTest.class         // 5. 评论模块（发表、列表、树形结构）
})
public class CommunityApplicationTests {

    /*
     * 运行方式：
     * 1. IDEA中右键点击此类，选择 "Run CommunityApplicationTests"
     * 2. 或在命令行执行：mvn test
     *
     * 测试顺序：
     * - UserControllerTest: 测试用户注册、登录、认证
     * - UserProfileControllerTest: 测试个人资料、修改用户名、修改密码、上传头像
     * - ArticleControllerTest: 测试文章发布、列表、详情、点赞
     * - ArticleExtendedTest: 测试我的文章、点赞文章、草稿箱、模糊搜索
     * - CommentControllerTest: 测试评论发表、列表、树形结构
     *
     * 注意事项：
     * 1. 确保MySQL和Redis服务已启动
     * 2. 数据库已初始化（执行database-init.sql）
     * 3. application.yaml配置正确
     */
}
