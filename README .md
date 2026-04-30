# Craving 后端 API 文档

> 美食发现与社区分享平台 · Spring Boot 3 / Java 21

---

## 目录

- [技术栈](#技术栈)
- [接口规范](#接口规范)
- [认证方式](#认证方式)
- [统一响应格式](#统一响应格式)
- [错误码说明](#错误码说明)
- [用户模块](#用户模块)
- [帖子模块](#帖子模块)
- [评论模块](#评论模块)
- [当前进度](#当前进度)

---

## 技术栈

- **框架**：Spring Boot 3
- **运行时**：Java 21
- **数据库**：MySQL + MyBatis-Plus
- **缓存**：Redis（点赞状态、分布式锁）
- **安全**：Spring Security + JWT

---

## 接口规范

**Base URL**

```
http://<server>:8080
```

**Content-Type**

- 绝大多数接口：`Content-Type: application/json`
- 头像上传接口：`Content-Type: multipart/form-data`

---

## 认证方式

登录后获得 JWT Token，将其附加到受保护接口的请求头：

```
Authorization: Bearer <your_jwt_token>
```

Token 有效期 **24 小时**，过期或缺失将返回 `401`。

---

## 统一响应格式

所有接口均返回以下结构：

```json
{
  "code": 200,
  "msg": "success",
  "data": ...
}
```

| 字段   | 说明                                      |
|--------|-------------------------------------------|
| `code` | `200` 成功，`500` 业务错误，`401` 未认证，`403` 无权限 |
| `msg`  | 错误时包含具体错误信息                    |
| `data` | 业务数据，出错时为 `null`                 |

---

## 错误码说明

| HTTP 状态码 | `code` 字段 | 场景                                       |
|-------------|-------------|--------------------------------------------|
| 200         | 200         | 成功                                       |
| 200         | 500         | 业务错误（校验失败、用户名已存在、密码错误等），查看 `msg` 字段 |
| 401         | 401         | JWT 缺失 / 无效 / 已过期                  |
| 403         | 403         | 已认证但权限不足                           |

> ⚠️ **注意**：业务错误的 HTTP 状态码仍为 `200`，前端须检查响应体中的 `code` 字段，而非 HTTP status。

---

## 用户模块

### 登录

```
POST /api/user/login
```

**权限**：公开

**请求体**

| 字段       | 类型   | 必填 | 说明               |
|------------|--------|------|--------------------|
| `username` | string | ✓    | 用户名             |
| `password` | string | ✓    | 明文密码，服务端加密 |

**请求示例**

```json
{ "username": "alice", "password": "123456" }
```

**响应示例**

```json
{
  "code": 200,
  "msg": "success",
  "data": "eyJhbGciOiJIUzI1NiJ9..."
}
```

`data` 为 JWT Token 字符串。

---

### 注册

```
POST /api/user/register
```

**权限**：公开

**请求体**

| 字段       | 类型   | 必填 | 说明           |
|------------|--------|------|----------------|
| `username` | string | ✓    | 必须唯一       |
| `password` | string | ✓    | 存储为 BCrypt 哈希 |

**响应示例**

```json
{ "code": 200, "msg": "注册成功", "data": "注册成功" }
```

---

### 验证 Token 有效性

```
GET /api/user/me
```

**权限**：需登录

**响应示例**

```json
{ "code": 200, "msg": "success", "data": "你已通过 Spring Security 认证" }
```

---

### 获取当前用户资料

```
GET /api/user/profile
```

**权限**：需登录

**响应示例**

```json
{
  "code": 200,
  "data": {
    "id": "1",
    "username": "alice",
    "avatar": "http://...",
    "email": null,
    "bio": null,
    "createTime": "2025-01-01 10:00:00",
    "postCount": 12,
    "totalLikes": 88,
    "level": 1,
    "coins": 0,
    "followerCount": 5,
    "followingCount": 3
  }
}
```

> ⚠️ `id` 为 `Long` 类型，因 JavaScript `Number` 精度限制，后端已序列化为**字符串**。前端请勿将其转换为 `Number`。

---

### 获取指定用户资料

```
GET /api/user/profile/{userId}
```

**权限**：需登录

**路径参数**

| 参数     | 类型   | 说明       |
|----------|--------|------------|
| `userId` | Long   | 目标用户 ID |

**响应结构**：同 `GET /api/user/profile`。

---

### 修改用户名

```
PUT /api/user/update/username
```

**权限**：需登录

**请求体**

| 字段          | 类型   | 必填 | 说明     |
|---------------|--------|------|----------|
| `newUsername` | string | ✓    | 必须唯一 |

**响应示例**

```json
{ "code": 200, "msg": "用户名修改成功", "data": "修改成功" }
```

> ⚠️ 修改用户名后，原 JWT Token 仍存储旧用户名，建议前端提示用户重新登录以刷新 Token。

---

### 修改密码

```
PUT /api/user/update/password
```

**权限**：需登录

**请求体**

| 字段          | 类型   | 必填 | 说明     |
|---------------|--------|------|----------|
| `oldPassword` | string | ✓    | 当前密码 |
| `newPassword` | string | ✓    | 新密码   |

**响应示例**

```json
{ "code": 200, "msg": "密码修改成功", "data": "修改成功" }
```

---

### 上传头像（文件）

```
POST /api/user/update/avatar
```

**权限**：需登录  
**Content-Type**：`multipart/form-data`

**表单字段**

| 字段   | 类型 | 限制                         |
|--------|------|------------------------------|
| `file` | File | 仅限图片（`image/*`），最大 2MB |

**响应示例**

```json
{
  "code": 200,
  "msg": "头像修改成功",
  "data": "http://115.29.214.88:8080/uploads/avatars/uuid.png"
}
```

`data` 为新头像的可访问 URL，直接存储并展示即可。

---

### 设置头像（URL）

```
PUT /api/user/update/avatar
```

**权限**：需登录

**请求体**

| 字段        | 类型   | 必填 | 说明                 |
|-------------|--------|------|----------------------|
| `avatarUrl` | string | ✓    | 头像图片的完整 URL   |

**请求示例**

```json
{ "avatarUrl": "https://cdn.example.com/avatars/abc.jpg" }
```

---

## 帖子模块

### 发布帖子 / 保存草稿

```
POST /api/article/publish
```

**权限**：需登录

**请求体**

| 字段        | 类型     | 必填 | 说明                                  |
|-------------|----------|------|---------------------------------------|
| `title`     | string   | ✓    | 帖子标题                              |
| `content`   | string   | ✓    | 帖子正文                              |
| `isDraft`   | boolean  |      | `true` 保存为草稿；`false` 或省略则发布 |
| `imageUrls` | string[] |      | 图片 URL 列表（建议最多 9 张）        |
| `tags`      | string[] |      | 标签列表，如 `["美食","川菜"]`        |
| `topicId`   | Long     |      | 圈子/话题 ID；无则传 `null`           |

**请求示例**

```json
{
  "title": "成都必打卡的三家苍蝇馆子",
  "content": "第一家位于...",
  "isDraft": false,
  "imageUrls": ["https://cdn.example.com/img/a.jpg"],
  "tags": ["美食", "川菜"]
}
```

**响应示例**

```json
{ "code": 200, "msg": "操作成功", "data": "操作成功" }
```

---

### 帖子列表（分页）

```
GET /api/article/list
```

**权限**：公开

**查询参数**

| 参数       | 类型   | 默认值  | 说明                                      |
|------------|--------|---------|-------------------------------------------|
| `page`     | int    | `1`     | 页码（从 1 开始）                         |
| `size`     | int    | `10`    | 每页条数                                  |
| `sortType` | string | `"new"` | `new` 按最新排序；`hot` 按浏览量+点赞量排序 |

**响应示例**

```json
{
  "code": 200,
  "data": {
    "records": [ /* ArticleVO[] */ ],
    "total": 128,
    "size": 10,
    "current": 1,
    "pages": 13
  }
}
```

**ArticleVO 结构**

```json
{
  "id": "100",
  "title": "...",
  "content": "...",
  "summary": "...",
  "viewCount": 42,
  "likeCount": 8,
  "collectCount": 3,
  "shareCount": 1,
  "status": 1,
  "createTime": "2025-06-01 12:00:00",
  "userId": "1",
  "authorName": "alice",
  "authorAvatar": "http://...",
  "isLiked": false,
  "isCollected": false,
  "imageUrls": ["https://..."],
  "tags": ["美食"],
  "topicId": null
}
```

| 字段          | 说明                                         |
|---------------|----------------------------------------------|
| `id`          | string（Long 序列化）                        |
| `summary`     | 自动生成，正文前 100 字                      |
| `status`      | `0` 草稿，`1` 已发布                         |
| `isLiked`     | 仅在请求已认证时返回准确值                   |
| `isCollected` | 当前始终为 `false`（收藏功能待实现）         |

---

### 帖子详情

```
GET /api/article/{id}
```

**权限**：公开

**路径参数**

| 参数 | 类型 | 说明    |
|------|------|---------|
| `id` | Long | 帖子 ID |

返回单个 `ArticleVO`，每次调用**原子性地增加浏览量**。

---

### 点赞 / 取消点赞

```
POST /api/article/like/{id}
```

**权限**：需登录

**路径参数**

| 参数 | 类型 | 说明    |
|------|------|---------|
| `id` | Long | 帖子 ID |

调用一次点赞，再次调用取消点赞。每个用户+帖子对受 **5 秒分布式锁**保护，防止重复快速点击。

**响应示例**

```json
{ "code": 200, "msg": "操作成功", "data": "操作成功" }
```

---

### 指定用户的帖子列表

```
GET /api/article/user/{userId}
```

**权限**：公开

**路径参数**

| 参数     | 类型 | 说明      |
|----------|------|-----------|
| `userId` | Long | 作者用户 ID |

**查询参数**：`page`（默认 1）、`size`（默认 10）

返回 `Page<ArticleVO>`，仅包含已发布帖子（`status=1`），按最新排序。

---

### 我点赞的帖子

```
GET /api/article/liked
```

**权限**：需登录

**查询参数**：`page`（默认 1）、`size`（默认 10）

返回 `Page<ArticleVO>`。点赞状态存储于 Redis，若无点赞记录则返回空分页。

---

### 我的草稿

```
GET /api/article/drafts
```

**权限**：需登录

**查询参数**：`page`（默认 1）、`size`（默认 10）

返回 `Page<ArticleVO>`（`status=0`），仅返回当前用户自己的草稿。

---

### 关键词搜索

```
GET /api/article/search
```

**权限**：公开

**查询参数**

| 参数      | 类型   | 必填 | 说明                             |
|-----------|--------|------|----------------------------------|
| `keyword` | string | ✓    | 搜索词，匹配标题和正文           |
| `page`    | int    |      | 页码，默认 1                     |
| `size`    | int    |      | 每页条数，默认 10                |

**示例**

```
GET /api/article/search?keyword=麻辣&page=1&size=10
```

> ℹ️ 当前实现为 MySQL `LIKE` 模糊匹配，后续版本将替换为 Elasticsearch 语义搜索。

---

## 评论模块

### 发表评论 / 回复

```
POST /api/comment/add
```

**权限**：需登录

**请求体**

| 字段        | 类型   | 必填 | 说明                              |
|-------------|--------|------|-----------------------------------|
| `articleId` | Long   | ✓    | 目标帖子 ID                       |
| `content`   | string | ✓    | 评论内容（不可为空）              |
| `parentId`  | Long   |      | 父评论 ID（回复时填写，顶级评论省略或传 `0`） |

**顶级评论示例**

```json
{
  "articleId": 100,
  "content": "看起来很好吃！"
}
```

**回复示例**

```json
{
  "articleId": 100,
  "content": "同意！我昨天刚去过",
  "parentId": 55
}
```

**响应示例**

```json
{ "code": 200, "msg": "评论成功", "data": "评论成功" }
```

---

### 获取评论树

```
GET /api/comment/list/{articleId}
```

**权限**：公开

**路径参数**

| 参数        | 类型 | 说明       |
|-------------|------|------------|
| `articleId` | Long | 目标帖子 ID |

**响应示例**

```json
{
  "code": 200,
  "data": [
    {
      "id": "55",
      "content": "看起来很好吃！",
      "userId": "1",
      "username": "alice",
      "userAvatar": "http://...",
      "createTime": "2025-06-01 12:30:00",
      "parentId": "0",
      "children": [
        {
          "id": "56",
          "content": "同意！我昨天刚去过",
          "parentId": "55",
          "children": []
        }
      ]
    }
  ]
}
```

> ℹ️ 评论树已在**服务端**组装为嵌套结构，前端直接递归渲染 `children` 数组即可，无需自行处理父子关系。

## 当前进度

- 基本用户逻辑/帖子逻辑已经实现，后端junit测试全部通过
- 推荐算法/搜索算法已经在代码层实现但未正式测试
  - 推荐算法参考DIN，加入额外的attention维度；引入LLM动态调参实现推荐底层个性化
  - 搜索算法采用Embedding AI嵌入ES，实现多模态搜索
- 未考虑并发问题以及sql设计（后期优化方向）
- AI全部使用阿里云API，额度100w token

---

*Craving API Docs — CS183-2026 Project #17*
