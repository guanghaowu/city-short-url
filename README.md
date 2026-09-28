# CityShortUrl · 高并发短链系统

把长链接转成短链，浏览器访问短链 302 跳转到原地址，并提供访问统计。

个人项目（Java 后端实习求职作品）。核心关注点不是"能跑通"，而是**读主链路的性能与防护**：
短链系统的读远多于写（典型 100:1 以上），所以整个架构围绕"**让绝大多数请求不要碰到 MySQL**"来设计。

> **一句话概括**：读主链路四层拦截（布隆 → Redis → 空值标记 → MySQL），
> 2 万次非法短码请求 **DB 查询 0 次**；热点 key 失效瞬间 200 并发打进来 **DB 只查 1 次**；
> 访问日志走 RocketMQ 异步落库，跳转接口 **QPS 提升 50% 以上、p50 降低 50% 以上**。

---

## 目录

- [技术栈](#技术栈)
- [核心设计](#核心设计)
- [实测数据](#实测数据)
- [快速开始](#快速开始)
- [接口清单](#接口清单)
- [压测与验收](#压测与验收)
- [目录结构](#目录结构)
- [设计取舍与已知局限](#设计取舍与已知局限)

---

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21（LTS） |
| 框架 | Spring Boot 3.2.5 · Spring MVC |
| 持久层 | MyBatis-Plus 3.5.9 · Druid 1.2.23 |
| 存储 | MySQL 8.0 · Redis 7 |
| 消息 | RocketMQ 5.3.1（`rocketmq-spring-boot-starter` 2.3.1） |
| 鉴权 | JJWT 0.12.5 + 自定义 `HandlerInterceptor`（不引 Spring Security） |
| 其他 | Guava 33.2.1（BloomFilter）· Spring AOP（限流切面）· Knife4j 4.5.0（接口文档） |
| 部署 | Docker 多阶段构建 + Docker Compose |

---

## 核心设计

### 1. 读主链路：四层拦截，越便宜的检查越靠前

```
GET /{shortCode}
      │
      ▼
① 布隆过滤器（进程内存，纳秒级）── 判 false ⇒ 一定不存在，直接 404，连 Redis 都不碰
      │ 可能存在（有 1% 假阳性）
      ▼
② Redis 映射缓存（网络往返 ~0.5ms）── 命中 ⇒ 直接 302
      │ 未命中
      ▼
③ 空值标记 short:miss:{code}（TTL 60s）── 命中 ⇒ 说明"确实不存在"，直接 404
      │ 未命中
      ▼
④ MySQL（磁盘 IO ~5ms 起）── 查到则回填 Redis（TTL 24h + [0,1800]s 随机抖动）后 302
```

**为什么顺序不能反**：布隆是内存位运算，Redis 是网络往返，MySQL 是磁盘 IO，差着数量级。

**布隆过滤器为什么敢用本地（Guava）而不是 RedisBloom**：单实例部署时纳秒级、零网络开销。
代价是**多实例不一致**（A 实例新增的短链，B 实例的布隆里没有 → 误判 404），
所以启动时全量加载 + 创建时同步 `add()`。多实例部署必须换 RedisBloom / Redisson 共享位图。

**空值缓存为什么敢缓存"不存在"**：雪花 ID 单调递增、**永不复用**，
一个"不存在"的短码不可能 60 秒后突然存在。换自增 ID（删除后可能被重新分配）就不能这么干。

**布隆的异步加载窗口期**：布隆由守护线程异步加载以免阻塞启动，加载完成前布隆是空的 ——
会把真实短链也判成"不存在"。解法是 `AtomicBoolean ready`，未就绪时**保守放行**
（宁可多查一次库，不误杀）。

### 2. 缓存三剑客

| 问题 | 现象 | 本项目解法 |
|---|---|---|
| **穿透** | 大量不存在的 key 打到 DB | 布隆过滤器（前置拦截）+ 60s 空值标记（二次兜底，挡布隆 1% 假阳性） |
| **击穿** | 热点 key 失效瞬间 N 个并发同时回源 | Redis `SETNX` 互斥锁 + 双重检查 + 未抢到锁的线程自旋等缓存 |
| **雪崩** | 批量写入的 key 在同一秒集体失效 | TTL 叠加 `[0,1800]s` 随机抖动，把失效时间摊平 |

**释放锁用 Lua CAS（先比对 value 再删）**：若持锁线程因 GC 停顿超过锁 TTL，
锁已过期并被别人持有，直接 `DEL` 会删掉**别人的锁**，互斥保护失效。

**互斥锁 vs 逻辑过期**：本项目选互斥锁（不需要额外线程池，且"DB 查询 N→1"的对照容易量化）；
逻辑过期返回旧值 + 异步重建，不阻塞线程但会短暂读到旧数据。短链映射创建后基本不变，两者都能接受。

### 3. 滑动窗口限流（Redis ZSet + Lua）

创建短链接口按 **IP 限 60 次/分钟**，超出返回**真正的 HTTP 429**（不是 HTTP 200 + 业务码）。

**为什么不用固定窗口计数器**：固定窗口有经典的**临界点流量翻倍**缺陷 ——
限 60 次/分钟时，请求在 00:59 打满 60 次、01:00 又打满 60 次，1 秒内实际通过 120 次。

**为什么必须用 Lua**："清理滑出窗口的记录 → 计数 → 记账"三步必须原子。
拆成三条命令的话，并发下两个请求可能同时读到 count=59 然后都放行，实际放进去 61 条。

**失败策略 fail-open**：Redis 挂了是放行而不是全拒 —— 限流是保护手段而非业务前提。
（对比：鉴权必须 fail-close，宁可拒也不能放。）

### 4. 访问日志 MQ 异步化

跳转成功后只往 RocketMQ 投一条消息（`asyncSend`，**不等 broker ack**），
真正的 `INSERT` 由消费者线程做。请求线程从头到尾不碰 `access_log` 表。

**为什么用 `asyncSend` 而不是 `syncSend`**：判据是"消息丢了是功能事故还是统计误差"。
访问日志丢了只是少一条统计，用户完全无感 → 可以不等 ack。
（对比：我在另一个项目里做 AI 出题任务，丢了用户就永远等不到结果，那里必须 `syncSend` + 失败补偿。）

**fail-open**：broker 挂掉时跳转仍返回 302。要兜住两种失败形态 ——
① 无路由时 `asyncSend` 在**调用线程里**同步抛 `MQClientException`；
② 路由还在缓存里但 broker 没接住 → `SendCallback.onException`。

**404 不计入 PV**：只有解析成功才记日志，否则一个被反复扫描的失效短码会把 PV 刷得虚高。

---

## 实测数据

> 全部来自可复现脚本（`bash bench/run-bench.sh`），且每个优化都配了**把它关掉的基线组**。
> 完整分析、度量口径排查与已知局限见 **[`docs/项目B-压测报告.md`](../docs/项目B-压测报告.md)**。

### 缓存三剑客（Step 4）

| 场景 | QPS | p50 | p99 | DB 查询次数 |
|---|---|---|---|---|
| 缓存冷路径（2000 个码全回源） | 766.9 | 55.96ms | 342.2ms | **2000** |
| 缓存热路径（同样 2000 请求全命中） | **1784.1** | 23.69ms | **123.4ms** | **0** |
| 穿透：2 万随机非法码（布隆 **ON**） | **3229.5** | 13.94ms | **42.98ms** | **0** |
| 穿透对照（布隆 **OFF**） | 1382.8 | 30.29ms | 98.86ms | **20000** |
| 击穿：200 并发打失效热点 key（互斥锁 **ON**） | 542.0 | 237.3ms | 298.6ms | **1** |
| 击穿对照（互斥锁 **OFF**） | 222.5 | 793.7ms | 848.2ms | **200** |

> 「加锁反而更快」不是错觉：Druid `max-active=20`，不加锁时 200 个请求在 20 个连接上排队，
> 延迟被放大；加锁后只有 1 个线程查库（~10ms），其余 199 个自旋 50ms 后读缓存。
> **用「少数请求多等 50ms」换「整体不再排队」。**

### MQ 异步化（Step 7，三轮对照）

| 访问日志写入方式 | QPS | p50 |
|---|---|---|
| `SYNC`（请求线程直接 INSERT，基线） | 560 ~ 709 | 64 ~ 83ms |
| `MQ`（asyncSend + 消费者落库） | 991 ~ 1464 | 18 ~ 32ms |
| `OFF`（完全不记日志，净开销下界） | 1618 ~ 1878 | 22 ~ 24ms |

**能站住的结论只有两条**（三轮方向 3/3 一致）：**QPS 提升 50% 以上**、**p50 降低 50% 以上**。

> ⚠ **p99 不写**：三轮方向都不一致（−21% / +3% / +63%），甚至出现过
> 「MQ 的 p50 比完全不记日志还低」的异常值 —— 记日志比不记还快在物理上说不通。
> **这套环境能可靠回答「哪个更快」，回答不了「快多少」。**

### 验收断言

| 脚本 | 断言数 | 结果 |
|---|---|---|
| `scripts/verify-step3.sh`（跳转 + 缓存 + 布隆） | 12 | PASS |
| `scripts/verify-step4.sh`（缓存三剑客） | 12 | PASS |
| `scripts/verify-step5.sh`（限流） | 9 | PASS |
| `scripts/verify-step6.sh`（管理接口 + 越权） | 25 | PASS |
| `scripts/verify-step7.sh`（MQ 异步化） | 18 | PASS |
| `mvn test`（雪花算法 + base62） | 9 | PASS |

---

## 快速开始

**前置**：Docker（含 Compose v2）。**不需要**本地装 Java / Maven / MySQL / Redis。

```bash
git clone <repo-url> && cd short-url-system
docker compose up -d
```

启动 5 个容器：应用 / MySQL / Redis / RocketMQ NameServer / RocketMQ Broker。
首次启动应用约 10~20 秒（含布隆过滤器全量加载）。

```bash
# 健康检查：会返回 redis / db 状态，以及当前生效的防护开关
curl -s localhost:8082/api/health
```

```json
{
  "code": 0,
  "data": {
    "service": "city-short-url",
    "status": "UP",
    "redis": "UP",
    "db": "UP",
    "features": {
      "bloomFilter": "ON",
      "cacheMutex": "ON",
      "nullCache": "ON",
      "rateLimit": "ON",
      "ttlJitterSeconds": 1800,
      "accessLog": "MQ"
    }
  }
}
```

> `features` 段是为了**压测自证**：对照组需要把布隆/互斥锁/限流关掉，
> 但"我关掉了"不能靠嘴说 —— 脚本会先读这个字段确认开关真的生效，再开始打流量。

**三个入口**：

| 地址 | 说明 |
|---|---|
| http://localhost:8082/ | 演示页面（注册登录 → 生成短链 → 跳转测试 → 访问统计） |
| http://localhost:8082/doc.html | Knife4j 接口文档 |
| http://localhost:8082/api/health | 健康检查 |

**端口占用**（刻意与其他项目隔离，方便同时运行）：

| 组件 | 宿主端口 |
|---|---|
| 应用 | 8082 |
| MySQL | 3308 |
| Redis | 6380 |
| MQ NameServer | 9877 |
| MQ Broker | 10919 / 10921 |

---

## 接口清单

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/user/register` | — | 注册 |
| POST | `/api/user/login` | — | 登录，返回 JWT |
| GET | `/api/user/me` | ✅ | 当前登录用户（前端登录态校验用） |
| POST | `/api/short/create` | ✅ | 创建短链（**限流：60 次/分钟/IP**） |
| GET | `/api/short/info/{shortCode}` | ✅ | 短链详情（**校验归属**） |
| GET | `/api/short/my` | ✅ | 我的短链（分页，size 上限 100） |
| DELETE | `/api/short/{shortCode}` | ✅ | 删除（软删，**同时清缓存**） |
| GET | `/api/short/stats/{shortCode}` | ✅ | 访问统计 PV/UV（**校验归属**） |
| GET | `/api/health` | — | 健康检查 + 生效开关 |
| GET | `/{shortCode}` | — | **短链跳转（302）**，唯一的公开业务接口 |

### 两类接口，两套错误语义（刻意的设计）

| 场景 | 返回 | 为什么 |
|---|---|---|
| API 接口失败 | **HTTP 200** + `code != 0` | 前端只需判断业务码，不必解析 HTTP 状态 |
| 短链不存在 | **HTTP 404** + 极简 HTML | 客户端是**浏览器**，不是 SPA。包成 200 的话用户点开失效短链会看到一坨裸 JSON，且浏览器/爬虫/监控都无法通过状态码识别"页面不存在" |
| 触发限流 | **HTTP 429** | 429 是**协议语义**而非业务失败：网关、CDN、浏览器、HTTP 客户端库都认它，配合 `Retry-After` 能自动退避 |
| 未登录 / token 无效 | **HTTP 401** | 鉴权失败属于传输层语义，前端 axios 拦截器据此跳登录页 |

---

## 压测与验收

```bash
# 单元测试（雪花算法 + base62 编解码）
./mvnw -B test -Dtest='SnowflakeTest,Base62Test'

# 功能验收（共 76 项断言）
bash scripts/verify-step3.sh    # 12 项：跳转 / 缓存回填 / 布隆拦截
bash scripts/verify-step4.sh    # 12 项：穿透 / 击穿 / 雪崩
bash scripts/verify-step5.sh    #  9 项：滑动窗口限流
bash scripts/verify-step6.sh    # 25 项：管理接口 + 越权校验（含 4 项越权红线）
bash scripts/verify-step7.sh    # 18 项：MQ 异步落库 + fail-open

# 性能对照压测
bash bench/run-bench.sh all         # Step 4：缓存三剑客（约 10 分钟，含 2 次容器重建）
bash bench/run-bench.sh accesslog   # Step 7：MQ / SYNC / OFF 三组（约 5 分钟，含 4 次容器重建）
```

> **方法论：没有对照组的性能数字不可信。**
> 每个优化都配一个「把它关掉的基线组」，两组跑**完全相同的流量、并发、数据集**，
> 唯一变量是那个开关。开关通过环境变量注入，并由 `/api/health` 的 `features` 段自证生效：
>
> ```bash
> APP_BLOOM_ENABLED=false docker compose up -d city-url-app       # 关布隆
> APP_CACHE_MUTEX_ENABLED=false docker compose up -d city-url-app # 关互斥锁
> APP_RATELIMIT_ENABLED=false docker compose up -d city-url-app   # 关限流
> APP_ACCESS_LOG_MODE=SYNC docker compose up -d city-url-app      # 访问日志改同步写
> ```
>
> **并且单轮数字不下结论**：连跑三轮才能区分「方向稳定」与「幅度稳定」。

---

## 目录结构

```
short-url-system/
├── src/main/java/com/cityshorturl/
│   ├── controller/     RedirectController（302 跳转）/ ShortUrlController / UserController / HealthController
│   ├── service/        ShortUrlService（四层拦截 + 缓存三剑客）/ AccessLogService / BloomFilterService / UserService
│   ├── mq/             AccessLogProducer（asyncSend + fail-open）/ AccessLogConsumer / AccessLogMessage
│   ├── aspect/         RateLimitAspect（滑动窗口 Lua）
│   ├── annotation/     @RateLimit
│   ├── common/         JwtInterceptor / UserContext / RequestUtils / 全局异常处理 / Result
│   ├── entity/ mapper/ dto/ util/（SnowflakeIdGenerator / Base62）
│   └── config/         MybatisPlusConfig（分页插件）/ WebMvcConfig
├── src/main/resources/
│   ├── application.yml / application-dev.yml / application-docker.yml
│   ├── static/index.html   演示页面（单文件，零构建）
│   └── sql/                建表脚本
├── scripts/            verify-step3.sh ~ verify-step7.sh（功能验收）
├── bench/              bench.mjs（零依赖自写压测器）/ run-bench.sh（对照实验编排）/ RESULTS*.md
├── rocketmq/broker.conf
├── docker-compose.yml
└── Dockerfile          多阶段构建（maven:3.9-temurin-21 → eclipse-temurin:21-jre-alpine）
```

---

## 设计取舍与已知局限

> **主动写出来，而不是等面试官问。** 每个取舍都能回答"为什么这么选"。

### 为什么用雪花 ID 而不是自增 ID

自增 ID 需要中心化发号器（单点 + 性能瓶颈），且会**泄露业务量**（竞对看到 ID 就能推算日单量）。
雪花 ID = 1 位符号 + 41 位时间戳 + 5 位 datacenter + 5 位 worker + 12 位序列，本地生成、全局唯一。

**时钟回拨分级处理**：回拨 ≤5ms 自旋等待（对调用方无感），>5ms 直接抛异常 ——
宁可报错也不发重复 ID。

**为什么短码是 10 位而不是 6-8 位**：雪花 ID 当前量级约 `3.6e17`，而 base62 的 8 位上限只有
`2.18e14`，差了 3 个数量级。**雪花算法本质上做不到 6-8 位**；要更短只能改用号段模式的自增序列。

### 为什么不需要「生成后查重 + 冲突重试」

base62 编码是**双射**，只要雪花 ID 唯一，短码必然唯一。多一次 `SELECT` 只是白白多一次 DB 往返。
表上的 `UNIQUE(short_code)` 保留作为最后一道防线。

### 越权返回 403 而不是 404

403 会**确认"这个短码存在"**，对不可枚举的资源 ID 应该统一返回 404。
但本项目短码是公开的（任何人都能通过跳转接口枚举出哪些码存在），存在性本来就藏不住，
所以 403 没有额外泄露，换来更清晰的语义。**换成不可枚举的资源 ID 时，这个结论要重新评估。**

### 已知局限

| 局限 | 说明 | 改进方向 |
|---|---|---|
| **本地布隆多实例不一致** | A 实例新增的短链，B 实例判 404 | 换 RedisBloom / Redisson 共享位图 |
| **互斥锁阻塞线程** | 未抢到锁的线程自旋等待（最多 1s），高并发下占用 Tomcat 线程 | 改用「逻辑过期」：返回旧值 + 异步重建 |
| **访问日志会丢** | broker 挂 / 消息反复失败进死信队列 → 丢日志（跳转不受影响） | 对日志而言可接受；若是订单类数据必须换可靠投递 |
| **访问日志可能重复** | RocketMQ 是 **at-least-once**，消费成功但 ack 丢失会重投 → PV 略微偏大 | 本项目刻意不做去重（写放大不划算）；订单类需业务唯一键幂等 |
| **`X-Forwarded-For` 可伪造** | 换个请求头就能绕过限流 | 生产必须让 Nginx **覆盖**（而非追加）该头，或只信可信代理之后的 IP |
| **QPS 是保守下界** | `mybatis-plus` 的 `log-impl` 是 `StdOutImpl`，每条 SQL 同步写 stdout（本身就是瓶颈） | 压测前切 `NoLoggingImpl` |
| **绝对延迟含环境开销** | Docker Desktop on Windows 的端口转发占了大头（缓存命中的 p50 就有 14~24ms） | 同机部署 / Linux 环境复测 |
| **压测机与应用同机** | 压测客户端与应用容器抢同一台机器的 CPU / 网络 | 独立压测机 |
| **未做分库分表** | 表里保留了 `user_id` 作为分片键设计，但**没有接 Sharding-JDBC** | 数据量到千万级再上 |

---

## License

个人学习项目，可自由参考。
