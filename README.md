# Enterprise Copilot Agent

一个面向企业客服的 Java 21 / Spring Boot 3.4 后端。支持查询订单、按售后政策登记工单和 SSE 流式对话。MySQL 保存业务与审计数据，Redis 保存跨实例会话记忆和请求限流状态。

## 架构

```mermaid
flowchart LR
    Client["客户端"] --> API["Spring MVC<br/>同步 JSON / SSE"]
    API --> Guard["请求校验<br/>Redis + Lua 限流 / 提示词护栏"]
    Guard --> RAG["售后政策检索<br/>Markdown → 分片 → 向量与关键词"]
    RAG --> Agent["Spring AI ChatClient<br/>DeepSeek OpenAI 兼容接口"]
    Agent <--> Memory["Redis 会话记忆<br/>滑动窗口与摘要"]
    Agent <--> Tools["业务工具<br/>订单归属 / 政策与事务校验"]
    Tools --> DB[("MySQL<br/>订单、工单、对话审计")]
    API --> DB
    DB --> Metrics["实时指标 API"]
```

## 工程亮点

| 能力 | 设计 |
| --- | --- |
| 流式对话 | 同步和 SSE 使用同一套 ChatClient 与系统提示。SSE 保留原始文本分片，工具 JDBC 调用在工作线程执行。 |
| Redis 记忆 | 按用户与会话分桶，TTL 为 24 小时。超过 10 条消息后保留 1 条规则摘要和最近 6 条原话。提交完整问答，清空后在途请求无法恢复旧历史。 |
| 业务工具 | 支持查单、列订单、查工单和创建售后工单。先校验订单归属与售后政策，再在持有订单行锁的事务内检查并创建工单。重复的待审核申请返回原单号。 |
| 政策检索 | Markdown 分片通过向量与关键词联合排序，综合门槛为 0.65。默认用本地 N-gram 向量和 JSON 缓存，可配置独立的 OpenAI 兼容 Embedding 接口。政策原文仅注入当轮系统消息。 |
| 请求治理 | Redis Lua 使用服务端时钟，每个用户在滚动 60 秒内最多 15 次，超限返回 429 和 `Retry-After`。提示词护栏命中返回 403，审计只保存规则标记。 |
| 可观测性 | 每次对话按请求记录首见工具顺序和端到端耗时。MySQL 聚合请求量、会话量、平均延迟和工具排行，统计接口只返回聚合结果。 |

## Docker Compose 启动

需要 Docker Engine 与 Compose。Windows 可使用启用 WSL 2 的 Docker Desktop，镜像使用 Linux 容器。以下命令适用于 PowerShell 7，也可以将 `DEEPSEEK_API_KEY` 存入仓库根目录已被 Git 忽略的 `.env` 文件。

```powershell
$env:DEEPSEEK_API_KEY = Read-Host -MaskInput 'DeepSeek API Key'
docker compose up --build -d
docker compose ps
Invoke-RestMethod http://127.0.0.1:8080/api/health/check
```

暂时没有模型密钥时，可以直接启动并体验健康检查、知识库检索和指标接口。对话通过输入校验后会返回 503，提示配置密钥。

- 应用默认发布到宿主机 `127.0.0.1:8080`。端口冲突时可设置 `APP_PORT=18080`。MySQL 和 Redis 使用 Compose 内部网络。
- MySQL 首次启动时加载三张业务表及三笔演示订单。应用使用独立的 MySQL 用户。重新创建容器时，命名卷保留业务数据、Redis 数据和知识索引。
- Compose 提供本机演示密码。可通过 `MYSQL_ROOT_PASSWORD`、`MYSQL_USER` 和 `MYSQL_PASSWORD` 覆盖。首次创建 MySQL 数据卷时设置这些变量。
- 可选配置 `EMBEDDING_BASE_URL`、`EMBEDDING_API_KEY` 和 `EMBEDDING_MODEL` 切换向量模型。通过 `EMBEDDING_PATH` 覆盖默认的 `/v1/embeddings` 路径。

停止应用与中间件时运行 `docker compose down`。查看启动日志可运行 `docker compose logs -f app`。

## API 示例

下面的请求体适用于 `POST /api/chat` 和 `POST /api/chat/stream`。

```json
{
  "sessionId": "resume-demo-01",
  "userId": "U1001",
  "message": "请帮我查询订单 ORD-2026001 的状态"
}
```

```powershell
$base = 'http://127.0.0.1:8080'
$body = @{
    sessionId = 'resume-demo-01'
    userId = 'U1001'
    message = '请帮我查询订单 ORD-2026001 的状态'
} | ConvertTo-Json -Compress

Invoke-RestMethod "$base/api/chat" -Method Post -ContentType 'application/json; charset=utf-8' -Body $body
$body | curl.exe -N "$base/api/chat/stream" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' --data-binary '@-'
Invoke-RestMethod "$base/api/metrics/summary"
```

| 请求 | 用途 |
| --- | --- |
| `GET /api/health/check` | 验证 MySQL、Redis 和样例订单数据。 |
| `GET /api/knowledge/search?query=专线宽带已经完成了能退款吗` | 返回政策内容、综合分、文档名和条款编号。 |
| `POST /api/knowledge/reload` | 重新加载受信任的 Markdown，验证成功后原子切换索引。 |
| `DELETE /api/chat/memory/resume-demo-01?userId=U1001` | 清空用户会话的 Redis 记忆。 |
| `GET /api/metrics/summary` | 获取审计请求数、独立会话数、平均延迟和工具分布。 |

在 Compose 中，修改 `src/main/resources/docs/after_sales_policy.md` 后可以调用刷新接口。启动时配置 `KNOWLEDGE_RELOAD_TOKEN`，调用时通过 `X-Knowledge-Reload-Token` 传入同一个值。原生应用可设置 `KNOWLEDGE_POLICY_LOCATION=file:...` 指向外部文件，本机直连可使用回环地址调用刷新。

指标基于 MySQL 审计记录，包含通过入口检查的对话和 403 护栏拒绝记录。429 在 Redis 拦截，避免用超限流量放大 MySQL 写入。会话按 `(userId, sessionId)` 去重，工具排行按请求拆分工具名后计数。

## 本机开发与验证

使用 JDK 21、Maven、MySQL 8.0 和 Redis。先在 MySQL 执行 `src/main/resources/db/init.sql`，配置应用连接，再启动。

```powershell
mvn -B clean verify
mvn spring-boot:run
```

可使用 `MYSQL_USER`、`MYSQL_PASSWORD` 和 `MYSQL_PORT` 连接本机实例。Redis 默认连接 `127.0.0.1:6379`。

建议从三条链路复现验证。

1. 用独立的演示用户在 60 秒内发起 16 次对话请求。前 15 次进入业务处理，第 16 次返回 429。
2. 请求模型忽略已有指令并披露系统提示，确认同步及 SSE 返回 403，同时审计表只写入护栏规则标记。
3. 以 `U1002` 请求退款 `ORD-2026003`。专线完成状态触发政策拦截，MySQL 中的退款工单数量保持不变。

## 使用边界

这是本机演示与面试项目。HTTP 层目前以请求所带的 `userId` 标识用户。对外部署前，应通过可信登录身份绑定用户、组织与权限，并配置 HTTPS 和独立的运维访问控制。提示词特征检测为输入侧防御，订单归属、事务和政策校验由后端执行。售后工具创建的是待审核工单，资金处理由后续业务流程完成。
