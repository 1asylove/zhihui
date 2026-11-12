# 首页数据看板（dashboard-stats）

## 背景
原计划的 `charts` 表经核实不存在（旧项目遗留信息有误，已确认当前所有连接均无此表）。
改为对现有 `document` / `chunk` / `chat_session` 表做**实时聚合查询**，不新建冗余统计表。

## 接口
`GET /charts/overview`

- 认证：JWT 登录即可，无额外权限点（与 `GET /documents` 一致）
- 统计口径：**ADMIN 看全量；USER 只看自己**（沿用文档可见性规则）
- 一次请求返回看板全部数据，避免前端多次请求

## 返回（Result 信封的 data）

| 字段 | 类型 | 说明 |
|---|---|---|
| totalDocuments | long | 未删除文档总数 |
| successDocuments | long | status=SUCCESS 的文档数 |
| failedDocuments | long | status=FAILED 的文档数 |
| processingDocuments | long | status IN (PENDING, PARSING) 的文档数 |
| chunkCount | long | 未删除文档下的未删除切片数 |
| sessionCount | long | 未删除会话数（USER 仅自己的） |
| typeDistribution | [{name, count}] | 按 file_type 分组，count 降序 |
| recentUploads | [{date, count}] | 近 7 天每天上传数，无数据的日期补 0，date 格式 `yyyy-MM-dd` |

## 口径细节
- 所有统计只算 `deleted = 0`
- chunk 通过 `document_id` 关联 document 过滤可见性与删除状态
- recentUploads 起点 = 今天 - 6 天（含今天共 7 天），DB 只返回有数据的日期，Java 侧补零

## 验收
1. test1（ADMIN）调用返回全量统计
2. test2（USER）只统计到自己的文档与会话
3. 无文档时各项为 0、typeDistribution 为 `[]`、recentUploads 7 天全 0
4. 前端 `/dashboard` 渲染统计卡片 + 类型分布条形图 + 近 7 天上传趋势柱状图
