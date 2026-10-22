# z-observer 可观测性域

指标 / 日志 / 链路 + 告警引擎。回答「系统自己好不好」的那一层。

## 与第三方 Prometheus / Grafana 共存，是有意为之

**这不是「还没空自研 TSDB」的临时状态，是定下来的边界。**

- z-observer **不自建时序存储**。指标查询直读 z-env 里已经在跑的 Prometheus 本体。
- 过渡期与第三方 Prometheus / Grafana 并存。两者不冲突：采集面与告警面收归自研，
  存储面继续用现成的。
- 终态是 Grafana 仍然能读 z-observer 暴露的指标（保持 text format 兼容），
  也就是说 **z-observer 的 exposition 一直是 Prometheus 格式**，不是私有格式。
  自研轻 TSDB 是 M4 的可选项，不是主线 —— 降采样 / compaction / retention
  三件事每一件都够吃数周，而 M1 并不需要它们。

真正的断层从来不是「时序库」，而是「采集面 + 规则评估 + 告警出口」没有人回答。
z-env 编排的那套第三方监控栈是**被部署的第三方**，不是组织资产：
`z-mq` 的 `BrokerMetrics.toPrometheusFormat()` 零调用方、`z-rpc` 全仓无 exporter、
`z-graph` README 自陈 Prometheus 文本格式未实现。z-observer 补的就是这一段。

## 基本信息

| 项 | 值 |
|---|---|
| 父项目 | `io.github.yuku123:z-boot-parent:1.1.1`（中央仓已发的最新版） |
| 本仓坐标 | `io.github.yuku123:z-observer{,-core,-starter,-admin}:1.0.0`（根 pom 字面 version，无 `${revision}`） |
| 发布状态 | **未发中央** —— 2026-10-22 实测 `z-observer` / `-core` / `-starter` 三个坐标在中央仓均无构件 ⇒ 消费方拉不到，须先 `mvn install` 或等发中央 |
| admin | 独立宿主进程（`z-observer-admin`），HTTP 8080 + 采集口 14300 + 自指标 9100；看板是 `_frontend/` 里的 vite 应用 |

⚠ **父项目版本是现读的，挂之前先确认目标版本在中央仓上真的存在**：
`curl -sI https://repo1.maven.org/maven2/io/github/yuku123/z-boot-parent/<版本>/z-boot-parent-<版本>.pom`
本地 z-boot 仓的父项目火车头已抬到 1.1.2，但那一版**未发中央**（两个公共镜像实测 404，
本机 ~/.m2 里那份是本地 install 出来的）⇒ 挂了它，clean 机直接构建失败。
本仓因此挂 1.1.1；抬 parent 属发布动作，不在本仓改。

## 模块

| 模块 | 职责 |
|---|---|
| `z-observer-core` | 协议契约 + 模型 + 指标类型。零 Spring 依赖，业务侧与收端共用同一份 span/log 结构。 |
| `z-observer-starter` | 进程内 SDK：指标埋点与暴露、log4j2 Appender、显式 Trace API。业务进程引入这一个包。 |
| `z-observer-admin` | 收端：Netty 收日志/span、落存储、指标查询适配、规则引擎、告警出口、REST、看板。 |
| `_frontend/` | 看板（component 页面层 + suit 运行壳）。M2 收口进 z-opc 运维中心子区。 |

## 接入一个业务进程

```xml
<dependency>
  <groupId>io.github.yuku123</groupId>
  <artifactId>z-observer-starter</artifactId>
</dependency>
```

埋点、log4j2 上报、trace 三件事都由 starter 自动装配：应用自己的日志会被
Appender 批量送到收端，业务代码不需要为「可观测」写任何胶水代码。

显式链路（组织风格是显式代码，不走字节码增强）。`Tracer` 是实例，
持有上下文传播与上报，由 starter 注入；`Span` 可当作 try-with-resources 用：

```java
Span span = tracer.startRoot("order.create", SpanData.SpanKind.SERVER);
span.attribute("orderId", orderId);
try {
    ...
} finally {
    span.end();
}
```

## 指标口径

统一前缀 `z_observer_`。common tags 由各应用在
`z.observer.metrics.common-tags` 里声明，装配时统一挂到每条时序上
（本仓不硬编码 app/instance/env —— 各应用的命名口径不一样，由应用自己定）。

只发 classic histogram —— 自定义 bucket 那套在跨进程聚合时的合并语义不明确，
收益不抵成本。基数超限的时序注册会被拒绝并计进
`z_observer_series_rejected_total`，不会静默膨胀。

**暴露面是 starter 自己挂的 servlet，路径与 actuator 对齐：`/actuator/prometheus`，
但不吃 actuator。** 这两件事同时成立，别把它们拆开理解：

- 不引 actuator：观测 SDK 不该强迫消费方引入 actuator —— 它是可选能力，不是运行前提。
  引了就把「为了被观测」变成「为了用 SDK 必须先装一整套 actuator」。
- 不改路径：采方（Prometheus / z-env）按 `/actuator/prometheus` 配一份就够，
  不因为本 SDK 改配置。

代价是**同一个路径上不要再引 actuator**。同时存在时谁先注册谁赢，另一套指标静默不见，
而现象是"JVM 指标没了""进程内存不涨了"这种完全不像版本冲突的形状 —— 排查成本极高。
需要 JVM/HTTP 层面的指标时，要么走 actuator 的另一组路径，要么在本 SDK 里补，
但**不能两条都挂在 `/actuator/prometheus`**。

> 上面这条约束说的是 **SDK 侧**（`z-observer-starter`）不引 actuator。
> **收端宿主**（`z-observer-admin`）另有一份 actuator，只为暴露 `/actuator/health`
> 这条组织通用的存活口径（暴露面钉死 `health,info`，`show-details: never` ⇒
> 免鉴权响应体只有 `{"status":"UP"}`）。它挂在 `/actuator/health`，
> 与 SDK 的 `/actuator/prometheus` 不是同一条路径，两边不冲突。

## 日志与链路

传输走 Netty 私有 JSON 协议（D5，一步到位，不做 HTTP 批量过渡，省掉一整段迁移债）。
日志检索是 ES 式全文倒排，自研实现（D3），中文按 unigram + bigram 建索引，
无需分词器。链路由后端组树，前端只渲染不重算 —— 避免出现第二份真相。

## 告警

- 规则从 `application.yml` 声明式装配，也可 `POST /api/rules` 运行时注册。
  `GET /api/rules` 列出已装载的。
- 三类表达式：阈值 / absent / 组合布尔。
- 两段确认状态机：`OK → PENDING → FIRING → RESOLVED`。
  **本轮不再成立的实例必须显式收尾** —— 否则条件恢复时永远走不到 RESOLVED，
  「恢复通知跟随」这个验收项就发不出来。
- 两段确认有**两把闸门**，是「与」关系，缺一不可：
  - **时长闸门** `for-duration-millis`：条件要连续成立这么久；
  - **计数闸门** `for-hits`：条件要连续命中 N 个**评估周期**。
  命中-未命中交替的抖动场景，连续命中数永远攒不到 N ⇒ 永远不进 FIRING ——
  **不是把阈值调大凑出来的**，是「连续性」本身断了。
  条件断一轮 ⇒ 待确认作废 ⇒ 显式回退 `PENDING → OK`，**确认计数清零重来**。
  确认次数落在告警实例的 `hitCount` 上（`GET /api/alerts` 可读，
  `GET /api/rules` 里的 `forHits` 是这把闸门的配置值）。
- 去噪三件套：分组 group_by / 抑制 inhibit / 静默 silence。
  静默只压通知不改状态机；抑制需要成对写。
- 通知出口抽象成 `MessageChannel`，形状对齐 z-msg 渠道屏蔽层。
  **本仓不实现任何具体渠道**：钉钉/短信的凭据一律运行时从 z-mist 取，
  z-observer 里不留明文。默认挂一个写日志的渠道兜底 —— 渠道没配好时告警至少
  还留在收端日志里，不会因为「没渠道」整条消失。
- **z-msg 渠道是跨进程 HTTP 的**（`z.observer.notify.z-msg.base-url`）：
  需求 009 §1.3 的红线是 z-msg 与其它进程只能跨进程 HTTP，而 z-msg-core 又绑死
  MyBatis 数据源，把它拖成硬依赖会让收端在没有 ctc 数据源的环境里起不来。
  固定告警走 z-msg 侧的模板（需求 009 D4：固定文案走模板审核流），
  收件人按 `credentialRef` 运行时从 z-mist 取明文 —— **收端配置里没有凭据**。
- **告警文案固定带五项**：应用名 / 实例 / 规则名 / 触发值 / 时间。
  恢复通知用 `[恢复]` 前缀 + 正文「通知类型: 恢复通知（告警已消除）」与告警通知区分，
  只靠标题区分不行 —— 短信被渠道截断时两条会长得一样。
- **z-msg 不可用时：重试 → 本地落盘待发 → 后台补发，绝不静默丢。**
  待发目录 `z.observer.notify.resilient.spool-dir`（默认 `./data/notify-spool`），
  落盘文件里只有通知正文与结构化字段，**没有凭据**；
  补发次数用尽的挪进 `failed/` 留痕，不删。

### 60 秒预算拆解（M1 口径：进程挂掉 60 秒内通知到人）

| 段 | 取值 | 谁负责 |
|---|---|---|
| 抓取周期 | ≤ 5s | Prometheus 抓 `up{job=...}`（目标挂 ⇒ `up=0`） |
| 评估周期 | ≤ 15s | 收端规则评估循环（`evaluate-interval-millis`） |
| pending 确认 | 30s | `for-duration-millis`（条件要连续成立这么久才进 FIRING） |
| 渠道送达 | ~1s | z-msg 批量提交 → 渠道 provider → 收件人 |
| **合计（最坏）** | **≈ 51s** | 落在 60s 内 |

实测（真实进程演练，两轮均为 **42.8s** 到达、**14.8s** 恢复）见
`z-opc-foundation-lead/002_项目需求/003_定时任务空间/evidence/TASK-20261012-008/`。
⚠ 演练证明的是「告警真的从被杀进程走到 z-msg 渠道层并发出去了」；
最后一跳「到人（真实手机/钉钉群）」需要一条渠道凭据（z-mist 里放 + z-msg 侧渠道配置），
本组织目前没有 —— 详见那张卡的结论。

### absent 的判据

不用「查询结果为空」，用「**这条时序多久没被采到了**」。

Prometheus 的 instant 查询默认回看 5 分钟，进程刚挂时它照样返回最后一个点，
要等满 5 分钟 staleness 才判消失 —— 而 M1 的口径是 60 秒。
但也不能只看 `lastSeenMillis`：返回 -1 意味着这条时序**从来没被采到过**，
那是 job 名写错，不是进程挂了。这两种「没数据」必须分开，
否则值班的人会为一个拼错的 job 名折腾半天。

另有一个容易踩的点：**每轮都在变的值不能进 alert labels**。
labels 参与 fingerprint，每轮都变的话每轮都被当成一条新告警，
PENDING 每次从头开始，确认时长永远攒不满，通知一次都发不出去。
运行期表现是「状态停在 PENDING」，看起来像在工作。变化量进 annotations。

## 指标查询面（`MetricQuery`）与降级口径

M1 不带存储，直读 z-env 已有 Prometheus 本体（D2 过渡方案 B）。
查询面从第一天就抽成接口 —— **后置换自研轻 TSDB 时实现本接口即可，上层不改**
（规则引擎、控制台、REST 层一行不动）。

| 方法 | 语义 | Prometheus 端点 | 结果形态 |
|---|---|---|---|
| `instant(promql, timeMillis)` | **瞬时值** | `GET /api/v1/query` | vector |
| `range(promql, start, end, step)` | **区间向量** | `GET /api/v1/query_range` | matrix |
| `scalar(promql, timeMillis)` | **标量** | `GET /api/v1/query` | scalar |
| `lastSeenMillis(promql)` | 某时序最近一次被采集到的时间（-1 = 从来没有） | `GET /api/v1/query_range` | matrix |

支持的 PromQL 形态是**子集**。适配层**不解析 PromQL**（整串透传给 Prometheus），
所以语法上 Prometheus 收的都收；下面列的是**规则引擎实际用到**、自研 TSDB 也必须覆盖的：

- 单指标名：`z_observer_frames_total`
- 标签匹配（`=` `!=` `=~` `!~`）：`up{job="z-observer"}`、`up{job=~"z-.*"}`
- 区间向量函数：`rate(m[5m])`、`irate(m[5m])`、`increase(m[1h])`、`delta(m[1h])`
- 聚合：`sum` / `count` / `avg` / `max` / `min`，含 `sum by (job) (m)`
- 标量：`scalar(m)`、常量算术（`1+1`）
- 比较与布尔组合：`m > 0`、`m1 > 0 and m2 > 0`

**不做**（规则引擎用不上，自研 TSDB 也不必为它们写解析）：子查询（`m[5m:1m]`）、
`offset` / `@` 修饰符、recording rule 引用、`label_replace` 等改标签的函数。

### 降级：查询失败 ≠ 告警静默

| 情形 | 表现 |
|---|---|
| Prometheus 不可达 / HTTP 错误码 / 响应不合法 / PromQL 语法错 | 抛 `PrometheusQueryException`（消息带 **promql + URL + 根因**）+ 一行 WARN 日志 + 失败计数 +1 |
| 查询成功但**没有这条时序** | 返回空列表（业务事实，不是故障，不计失败） |
| 查询失败时的**规则状态** | **一个字都不动** —— 不被读成「恢复了」，也不被读成「实例挂了」 |
| 失败的可见度 | `z_observer_rule_eval_errors_total` 上涨 + `/api/alerts` 的 `evaluateErrorCount` 上涨 + 日志 |

实测（把 `Z_OBSERVER_PROMETHEUS_URL` 指向一个没人监听的端口，3 条规则 / 5 秒评估周期）：
`z_observer_rule_eval_errors_total` 30 秒内 **6 → 24**（= 3 条规则 × 12 次/分），
同时 `active_alerts` **恒为 0** —— 失败看得见，但**不冒充业务事实**。
对照组（同一份代码、Prometheus 可达）：错误计数 **0**、失败日志 **0** 行、查询正常返回。
证据：`z-opc-foundation-lead/002_项目需求/003_定时任务空间/evidence/TASK-20261012-006/`。

⚠ **谁来观测 z-observer 自己**：失败计数只在 z-observer 自己的 `/metrics` 上，
要有别的 Prometheus 来抓它（或由 z-ops 代看）。生产上 `Z_OBSERVER_PROMETHEUS_URL`
必须**显式注入** —— 不注入就落默认 `http://127.0.0.1:9090`，而集群里未必有 Prometheus 在那儿。

## 本地起

```bash
mvn clean install

# 收端（默认 8080，采集端口 14300）
# ⚠ 鉴权是 fail closed 的：本地起也要给密钥，否则进程**启动失败**并说清缺哪个环境变量。
#    生产走 Secret → 环境变量；本地临时给一个即可（值不进仓）。
export Z_OBSERVER_ADMIN_AUTH_TOKEN=local-admin-token
export Z_OBSERVER_COLLECTOR_TOKEN=local-collector-token
java -jar z-observer-admin/target/z-observer-admin.jar \
  --z.observer.prometheus-url=http://127.0.0.1:9090 \
  --z.observer.scrape-urls=http://127.0.0.1:9100/metrics

# 收端起来了？（/actuator/health 免鉴权；业务查询面要带 bearer）
curl -s http://127.0.0.1:8080/actuator/health

# 看板
cd _frontend/z-observer-component && npm install && npm run build
cd ../z-observer-suit && npm install
LOCAL_SIBLINGS=1 npm run dev     # 3020，代理 /api/ 到收端 8080
```

配置项一律无凭据字段：真值唯一居所是 `z-opc-foundation-lead/004_重要秘钥/`。

## 入站鉴权（卡 TASK-20261022-017 第 1/4 项）

收端有两道闸门，两道都 **fail closed**（缺密钥 ⇒ 启动失败，不肯「无鉴权地跑起来」）：

| 面 | 闸门 | 密钥来源 |
|---|---|---|
| 管理面 8080（NodePort 31891） | bearer 令牌，**读端点与写端点一起挡** | `Z_OBSERVER_ADMIN_AUTH_TOKEN` |
| 采集口 14300（NodePort 31430） | 共享密钥 + HMAC 握手（nonce 每次换 + 收端拒重放） | `Z_OBSERVER_COLLECTOR_TOKEN` |

- 密钥**只从环境变量 / Secret 取**，不进配置类、不进配置文件、不进仓。
- 管理面留了两条免鉴权的**存活**路：`/api/internal/health`（只答活着不活着，**不带业务数据**）
  给 k8s 探针用，以及 `/actuator/health`（组织通用口径，同样只有 `{"status":"UP"}`）——
  探针不会带 Authorization 头，而拿业务查询面当探针路径 = 在公开面上留了个业务读端点。
- SDK 侧（`z-observer-starter`）用 `z.observer.transport.auth-token` 配同一个采集口密钥；
  收端开了鉴权而 SDK 没配 ⇒ 连接被断（这是对的）。

## 当前阶段

M1（指标 + 告警最小闭环）已闭环。M2（日志前端收口进 z-opc）/ M3（traceparent
透传三处缝）按需求卡 [007](https://github.com/z-opc-foundation/z-opc-foundation-lead/blob/main/002_项目需求/001_特性需求/007_z-observer_可观测性域/README.md)
的分阶段路线推进。

---

## 文档目录

本项目文档统一收口在 `_doc/` 下（八类固定编号表，桶按需存在）:

- [`_doc/001_arch/`](_doc/001_arch/) — 架构、接口、DB schema、协议、路线图
  - [`00-overview.md`](_doc/001_arch/00-overview.md) — 项目总览：模块架构、核心功能、对外集成与上下游、
    技术特点、目录结构
