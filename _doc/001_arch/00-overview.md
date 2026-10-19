# 项目总览

## 1. 项目简介

z-observer 是 z-opc-foundation 的**可观测性域**：指标 / 日志 / 链路 + 告警引擎，回答「系统自己好不好」
的那一层。

**边界是定下来的，不是临时状态：** 本仓**不自建时序存储** —— 指标查询直读 z-env 里已经在跑的
Prometheus 本体。过渡期与第三方 Prometheus / Grafana 并存：采集面与告警面收归自研，存储面继续用现成的。
终态是 Grafana 仍然能读 z-observer 暴露的指标（保持 text format 兼容），也就是说 **z-observer 的
exposition 一直是 Prometheus 格式**，不是私有格式。自研轻 TSDB 是可选项，不是主线。

补的正是这段断层：采集面 + 规则评估 + 告警出口这三件事没有第二份答案。

**Maven 坐标：** `io.github.yuku123:z-observer:1.0.0`（根 POM `packaging=pom`），
父项目 `io.github.yuku123:z-boot-parent:1.1.0`（`<relativePath/>` 留空）。

**技术栈：** Java 8 + Spring Boot 2.7.18 + Maven 多模块（3 个 reactor 模块）；
传输层用 Netty（私有 JSON 帧协议），指标 exposition 自己写 Prometheus 文本格式。

## 2. 模块架构

```
z-observer/
├── pom.xml                       # 聚合 POM：父 z-boot-parent:1.1.0，3 个 reactor 模块
├── z-observer-core/              # 契约层（28 文件 / 2541 行）：零 Spring 依赖
│                                 #   protocol（Frame / FrameCodec / FrameHeader / FrameType / FrameDecodeException）
│                                 #   transport（EventSink / EventCollector / BatchingReporter）
│                                 #   metric（Metric / MetricType / Counter / Gauge / Histogram / MetricRegistry /
│                                 #    Labels / MetricFamily / MetricSample / PrometheusTextWriter）
│                                 #   model（LogEvent / LogBatch / SpanBatch）
│                                 #   trace（Span / SpanData / Tracer / TraceContext / ThrowableInfo）
├── z-observer-starter/           # 进程内 SDK（9 文件 / 772 行）：业务进程引这一个包就完成埋点
│                                 #   metrics（PrometheusServlet / PrometheusHandler / ObserverMetricsAutoConfiguration /
│                                 #    ObserverMetricsProperties）
│                                 #   transport（ObserverTransportAutoConfiguration / ObserverTransportProperties /
│                                 #    NettyEventSink / FrameDecoder）
│                                 #   log（ObserverLogAppender：log4j2 Appender）
├── z-observer-admin/             # 收端（43 文件 / 4598 行）
│                                 #   collect（CollectorServer / FrameDispatcher / FrameDecoder / HeartbeatTracker）
│                                 #   store（LogStore / InvertedIndex / Analyzer / TraceStore）
│                                 #   scrape（ScrapeLoop / Scraper / TargetRegistry / TargetSource /
│                                 #    StaticTargetSource / ZConfigNamingTargetSource）
│                                 #   rule（RuleEngine / Rule / RuleSpec / Expression / Condition / Alert / AlertState /
│                                 #    InhibitRule / Silence）
│                                 #   notify（AlertDispatcher / AlertNotifier / MessageChannel）
│                                 #   metric（Series / MetricQuery / PrometheusHttpClient）
│                                 #   web（LogQueryController / TraceController / AlertQueryController / SelfMetricsController /
│                                 #    ApiExceptionHandler）
│                                 #   config（ObserverAdminApplication / ObserverAdminConfiguration / ObserverAdminProperties）
├── _frontend/
│   ├── z-observer-component/      # 页面层（axios + dayjs + @yuku123/z-frontend-common）
│   └── z-observer-suit/          # 运行壳（React 19 + antd 6，dev 代理 /api/ 到收端）
└── LICENSE
```

**依赖方向：** `z-observer-core` 零 Spring 依赖，业务侧与收端共用同一份 span / log / 帧结构，
两侧不会各写一遍协议导致漂移；`z-observer-starter` 依赖 core 做上报；`z-observer-admin` 依赖 core 做收端。

## 3. 核心功能

### 3.1 指标埋点与暴露（`z-observer-starter/metrics`）

统一前缀 `z_observer_`。`MetricRegistry` 支持 `Counter` / `Gauge` / `Histogram`；
`PrometheusTextWriter` 写 Prometheus 文本格式。common tags 由各应用在
`z.observer.metrics.common-tags` 里声明，装配时统一挂到每条时序上
（本仓**不硬编码** app / instance / env —— 各应用的命名口径不一样，由应用自己定）。

**只发 classic histogram** —— 自定义 bucket 那套在跨进程聚合时的合并语义不明确，
收益不抵成本。基数超限的时序注册会被拒绝并计进 `z_observer_series_rejected_total`，不会静默膨胀。

**暴露路径 `/actuator/prometheus`，但不吃 actuator。** 这两件事同时成立：
不引 actuator 是因为观测 SDK 不该强迫消费方引入可选能力；不改路径是让采方按
`/actuator/prometheus` 配一份就够。代价是同一个路径上**不要再引 actuator** ——
同时存在时谁先注册谁赢，另一套指标静默不见，现象是「JVM 指标没了」这种完全不像版本冲突的形状。
需要 JVM/HTTP 层面的指标时，要么走 actuator 的另一组路径，要么在本 SDK 里补，但不能两条都挂同一路径。

### 3.2 日志与链路传输（Netty 私有 JSON 协议）

`z-observer-core/protocol` 的 `Frame` / `FrameCodec` 定义帧格式，`z-observer-core/transport` 的
`EventSink` / `EventCollector` / `BatchingReporter` 负责收集与批量上报。
走 Netty 私有协议，不做 HTTP 批量过渡，省掉一整段迁移债。

`ObserverLogAppender` 是 log4j2 Appender：应用自己的日志会被批量送到收端，
业务代码不需要为「可观测」写任何胶水代码。

### 3.3 日志检索（ES 式全文倒排，自研）

`InvertedIndex` + `Analyzer` + `LogStore`。中文按 **unigram + bigram** 建索引，无需分词器：
单字保证召回（二字词任意组合都能被单字命中），二元组压掉单字带来的巨量误召回。
日文假名与韩文同样归进来 —— 它们也不用空格分词，走同一套 unigram+bigram 口径。

### 3.4 链路（`z-observer-core/trace`）

`Tracer` 是实例，持有上下文传播与上报，由 starter 注入；`Span` 可当 try-with-resources 用
（`tracer.startRoot("order.create", SpanData.SpanKind.SERVER)` → `span.attribute(...)` → `span.end()`）。
链路由后端组树，前端只渲染不重算 —— 避免出现第二份真相。

### 3.5 采集面（`z-observer-admin/scrape`）

常驻服务 pull。target 优先从 z-config naming 发现（订阅的服务名填在 `naming-services`），
naming 客户端不可用时退回 `scrape-urls`。**两个都空 = 采集层空转，会打一条 warn，不静默。**

### 3.6 规则引擎与告警出口（`z-observer-admin/rule`、`notify`）

- 规则从 `application.yml` 声明式装配，也可 `POST /api/rules` 运行时注册，`GET /api/rules` 列出已装载的。
- 三类表达式：`THRESHOLD`（阈值）/ `ABSENT`（时序消失）/ `BOOLEAN`（组合布尔）。
- 两段确认状态机：`OK → PENDING → FIRING → RESOLVED`。`FIRING → RESOLVED` 单独一个态而不是直接回 OK ——
  恢复通知是需求里明写的一项；本轮不再成立的实例必须显式收尾，否则条件恢复时永远走不到 RESOLVED。
- 去噪三件套：分组 `group_by` / 抑制 `inhibit` / 静默 `silence`。静默只压通知不改状态机；
  抑制需要成对写。
- 通知出口抽象成 `MessageChannel`，形状对齐 z-msg 渠道屏蔽层。**本仓不实现任何具体渠道** ——
  钉钉 / 短信的凭据一律运行时从 z-mist 取，z-observer 里不留明文；
  默认挂一个写日志的渠道兜底，渠道没配好时告警至少还留在收端日志里。

### 3.7 absent 的判据

不用「查询结果为空」，用「**这条时序多久没被采到了**」。Prometheus 的 instant 查询默认回看 5 分钟，
进程刚挂时它照样返回最后一个点，要等满 5 分钟 staleness 才判消失。
但也不能只看 `lastSeenMillis`：返回 -1 意味着这条时序**从来没被采到过** —— 那是 job 名写错，
不是进程挂了。这两种「没数据」必须分开。

另一个易踩点：**每轮都在变的值不能进 alert labels**。labels 参与 fingerprint，每轮都变则每轮都被当成
一条新告警，PENDING 每次从头开始、确认时长永远攒不满，通知一次都发不出去 ——
运行期表现是「状态停在 PENDING」，看起来像在工作。变化量应进 annotations。

## 4. 对外集成 / 上下游

**收端（`z-observer-admin`）三个端口（`z-observer-admin/src/main/resources/application.yml`）：**

| 端口 | 用途 | 配置键 |
|---|---|---|
| `8080` | 收端 HTTP（REST / 看板） | `server.port` |
| `14300` | 收端监听端口（SDK 按此地址上报） | `z.observer.collector-port` |
| `9100` | 收端自身指标暴露 | `z.observer.metrics-port` |

**SDK 侧（`z-observer-starter`）暴露面：** `/actuator/prometheus`（默认端点，
`z.observer.metrics.endpoint` 可改），由 starter 自己挂 servlet，**不引 actuator**。

**上游依赖：** 父链 `z-boot-parent` → 地板 `z-boot-dependencies` + 兄弟仓权威表 `z-boot-fleet`；
`z-util-parser-json`（收端解析）、Netty（`netty-transport` / `netty-codec`）、log4j2（`log4j-api` /
`log4j-core` / `log4j-slf4j-impl`）。

**下游 / 上游服务关系：**

- **下游消费方** —— 任何业务进程引 `z-observer-starter` 一个坐标即完成埋点、日志上报与 trace；
  看板由 `_frontend/z-observer-suit`（React 19 + antd 6）承载，dev 时把 `/api/` 代理到收端。
- **上游依赖外部实例** —— 指标查询直读 Prometheus 本体
  （`z.observer.prometheus-url`，默认 `http://127.0.0.1:9090`）；采集 target 走 z-config naming 或
  `z.observer.scrape-urls`。
- **凭据：** 配置项一律无凭据字段 —— 告警渠道凭据运行时从 z-mist 取，真值唯一居所是
  `z-opc-foundation-lead/004_重要秘钥/`。

**REST 端点（实测）：** `/api/rules`（GET 列出 / POST 运行时注册）、`/api/alerts`、`/api/silences`、
`/api/inhibits`、`/api/metrics/query`、`/api/metrics/query-range`（以上六条同挂 `AlertQueryController`）、
`/api/logs` 与 `/api/logs/status`（`LogQueryController`）、`/api/traces/{traceId}`（`TraceController`），
以及收端自身指标 `/metrics`（`SelfMetricsController`，Prometheus 文本格式）。

## 5. 技术特点

1. **契约层零 Spring 依赖：** `z-observer-core` 不引 Spring，SDK 侧与收端侧共用同一份 span / log / 帧结构，
   两侧各写一遍协议必然漂移。
2. **不自建时序存储是定下来的边界：** 指标查询直读 Prometheus 本体，exposition 保持 Prometheus 文本格式
   兼容 —— 采集面与告警面自研，存储面用现成的。
3. **暴露路径与 actuator 的耦合是刻意设计：** `/actuator/prometheus` 保持不变（采方只配一份），
   同时不引 actuator（观测 SDK 不该强迫消费方装一整套 actuator）；代价是同一路径上不能两条并存。
4. **日志检索自研但不分词：** 中文走 unigram + bigram，日文假名与韩文同口径，不引入分词器依赖。
5. **absent 判据是「多久没被采到」而不是「结果为空」：** Prometheus instant 查询回看 5 分钟会让
   「进程刚挂」看起来仍有数据；而 `lastSeenMillis == -1`（从未被采到）与「挂了」必须分开，
   否则值班的人会为一个拼错的 job 名折腾半天。
6. **告警 labels 里不能放每轮都变的值：** labels 参与 fingerprint，每轮变则每轮算新告警，
   PENDING 永远攒不满确认时长，现象是「状态停在 PENDING」——看起来像在工作，实际一次通知都发不出。
7. **通知出口抽象但不自建渠道：** `MessageChannel` 形状对齐 z-msg 渠道屏蔽层，
   凭据运行时从 z-mist 取、仓内不留明文，默认写日志渠道兜底。
8. **显式 trace 而非字节码增强：** 组织风格是显式代码，`Tracer` / `Span` 由 starter 注入，
   链路组树在后端、前端只渲染不重算。
9. **采集层空转不静默：** naming 与 scrape 两个来源都空时打一条 warn，而不是假装在采。

## 6. 目录结构

本仓 Java 包根为 `com.zifang.z.observer`，按 Maven 模块切分：

```
com.zifang.z.observer
├── core/          # z-observer-core（零 Spring）
│                 #   protocol / transport / metric / model / trace
├── starter/       # z-observer-starter
│                 #   metrics（暴露 servlet + 自动装配 + 属性）/ transport（Netty 上报 + 属性）/ log（log4j2 Appender）
└── admin/         # z-observer-admin
                  #   collect（收帧）/ store（日志倒排 + 链路）/ scrape（采集）/ rule（规则与状态机）/
                  #   notify（出口）/ metric（查询适配）/ web（REST）/ config（主类与属性）
```

**规模（`src/main/java` 实测）：** admin 43 文件 / 4598 行，core 28 文件 / 2541 行，
starter 9 文件 / 772 行。

**测试：** `src/test` 共 29 个 Java 文件。
