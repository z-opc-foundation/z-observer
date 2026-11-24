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
| 父项目 | `io.github.yuku123:z-boot-parent:1.2.2`（中央仓已发的最新版） |
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

## D1 采集层（常驻服务 pull + push 边车）

**pull 为主 + push 边车**：pull 的目标健康本身可观测（抓不到 = 挂了，absent 天然成立）；
push 边车收口 z-schedule 批任务这类 pull 覆盖不到的短生命周期进程。

### pull 采集器

- **target 清单**：`z.observer.naming-services`（订阅 z-config naming）优先，
  `z.observer.scrape-urls`（静态兜底）次之；两个都空 = 采集层空转（打 warn，不静默）。
- **采集路径**默认 `/actuator/prometheus`（= SDK 的 `z.observer.metrics.endpoint` 默认值）。
  ⚠ **不是 `/metrics`** —— 那条路径是**收端自己**暴露自指标的地方，SDK 并不暴露它；
  按端口拼 target 时拼 `/metrics` ⇒ 采过去全是 404，而失败原因看起来像「服务挂了」。
- **exposition 解析**在 `z-observer-core` 的 `PrometheusTextParser`（text format 0.0.4）：
  读 `# HELP` / `# TYPE` / 标签 / 特殊值（`NaN` / `±Inf`）/ 可选时间戳。
  **坏行不抛异常，只记账**（`ParseResult.errors()` 带行号与原因）：一个 target 的
  exposition 里混进一行坏数据时，把整份丢掉会连带扔掉另外几十条好时序，
  全盘接受又等于把半截数据当真 —— 折中是**好数据照收、坏数据记账**。
- **解析失败按失败论**：那一轮对这个 target 的数据是不完整的，当成功入库等于用半截数据
  覆盖上一轮的好数据，而面板上看不出任何异常。

### 「抓不到」事件 = absent 数据源

抓不到必须落成**机器能读到的结构**，不能只是一行日志 —— absent 告警靠的就是
「读到这个 target 现在缺席」：

| 产物 | 位置 | 说明 |
|---|---|---|
| `ScrapeEvent` | `admin/scrape/` | 事件类型：`SCRAPE_SUCCEEDED` / `SCRAPE_FAILED` / `PARSE_FAILED` / `TARGET_VANISHED` / `TARGET_REAPPEARED` |
| `ScrapeStatus` | `admin/scrape/` | 每个 target 的当前状态（最后成功/失败时刻、连续失败次数、最后一条事件） |
| `ScrapeStatusRegistry` | `admin/scrape/` | 状态 + 事件滚动窗口（默认 500 条）；`absentTargets()` 就是缺席名单 |
| exposition | `ScrapeStatusRegistry#toExposition()` | `z_observer_scrape_target_up{target="…"}` / `z_observer_scrape_target_absent{target="…"}` / `z_observer_scrape_failures_total` / `z_observer_scrape_parse_failures_total` |

⚠ **接线状态（别当成已生效）**：采集循环（`ScrapeLoop`）已经在产出这些事件与状态，
但**它们还没有接到收端的 `/metrics`，也没有接到规则引擎** ——
那两处分别要改 `admin/config/ObserverAdminConfiguration` 与 `admin/web/SelfMetricsController`，
不在采集层卡的文件授权范围内。
⇒ 现在能读到的入口是 `ScrapeLoop#status()`（进程内 API）。
把它变成「Prometheus 抓得到 / 规则消费得到」是**下一步**，别当成本卡已完成。

### push 边车（z-schedule 批任务）

`z-observer-starter` 的 `ScheduleJobLogSidecar`：**独立进程形态**，
只读轮询 z-schedule 的 `GET /api/schedule/joblog/list`，把任务执行日志转成指标事件
（`z_observer_schedule_job_execution_total{task="…",status="success|failure"}` 等），
并在自己的端口上按 Prometheus 文本格式暴露。

- **z-schedule 侧零源码改动**：不加依赖、不改代码、不注册钩子 ——
  复用它**已经有的**任务执行日志（`JobLog`）与管理面 JSON 接口。
  改 z-schedule 的代价是每个域的调度仓都要跟着观测 SDK 走一遍。
- 边车是**常驻进程**（不是被观测的短命任务），所以它的指标由 Prometheus 抓 ——
  与「采集端拉被观测方」是同一个格式契约，方向反过来。
- 凭据（若 z-schedule 管理面要登录态）走 `Z_OBSERVER_SIDECAR_AUTH_HEADER` 环境变量，
  **值不进仓**。

### 怎么验（本地，一条命令）

```bash
# 采集层验收演练：5 个真实进程（python3.11 起 HTTP 服务，暴露 SDK writer 产出的
# text 0.0.4 exposition）→ 连续抓 → 中途杀掉一个 → 抓不到事件 → 缺席名单 → 恢复。
# 完整 10 分钟那场（类名不匹配 surefire 默认包含规则，要显式指定才跑）：
mvn -pl z-observer-admin -am test -Dtest=ScrapeAcceptanceHarness \
    -Dzobserver.scrape.acceptance.seconds=600 \
    -Dzobserver.scrape.acceptance.out=/path/to/evidence

# 常规构建里跑的是同一条通路的短跑版（十几秒）
mvn -pl z-observer-admin -am test
```

产物（`rounds.jsonl` / `samples.jsonl` / `events.txt` / `status-exposition.txt` / `summary.json`）
落在 `-Dzobserver.scrape.acceptance.out` 指定的目录。

本轮（卡 `TASK-20261012-005`）的完整演练产物与生产侧只读取证在
`z-opc-foundation-lead/002_项目需求/003_定时任务空间/evidence/TASK-20261012-005/`。

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
- [`_doc/003_script/`](_doc/003_script/) — 构建与发布脚本（GPG passphrase 注入器）

---

<!-- z-opc-foundation-sibling-registry:v1 -->
## 🔗 兄弟仓全景（z-opc-foundation 组织）

本仓隶属于 [z-opc-foundation](https://github.com/orgs/z-opc-foundation/repositories) 组织——一人公司基座的全部自研件（现存 61 仓，含私有仓；口径 2026-11-24 GitHub org API 实读）。

| 仓 | 一句话说明 |
|---|---|
| [z-agent](https://github.com/z-opc-foundation/z-agent) | 云端 Agent 平台的库与 starter——ReAct/Plan 实现 + 注册中心 + REST 网关 + 只读控制面 |
| [z-agent-kernel](https://github.com/z-opc-foundation/z-agent-kernel) | Agent 框架基座——消息/工具/记忆/provider/会话 SPI + 轻量默认实现（纯 POJO 无 Spring） |
| [z-asset](https://github.com/z-opc-foundation/z-asset) | 企业资产台账——实物设备全生命周期 + 域名/证书/许可到期提醒 |
| [z-audit](https://github.com/z-opc-foundation/z-audit) | 审计与合规留痕——事件模型 + 哈希链 + 事件出口 SPI + 留存期策略 |
| [z-biz](https://github.com/z-opc-foundation/z-biz) | 商务外壳域——客户/合同/票据/对账四聚合 |
| [z-biz-erp](https://github.com/z-opc-foundation/z-biz-erp) | 进销存 ERP——商品/客商/采购/销售/库存流水/应收应付核销（z-biz 业务线） |
| [z-biz-security](https://github.com/z-opc-foundation/z-biz-security) | 业务数据安全防护入口——场景化策略注册 + 安全线四段流水线对外编排 |
| [z-boot](https://github.com/z-opc-foundation/z-boot) | Spring Boot Starter 聚合仓 + 版本权威（火车制）——唯一消费入口 z-boot-parent |
| [z-bot](https://github.com/z-opc-foundation/z-bot) | 本地 agent 应用——终端 CLI + HTTP/SSE web 控制台 + 多通道 gateway（飞书/钉钉/webhook） |
| [z-cache](https://github.com/z-opc-foundation/z-cache) | RESP2 协议兼容的分布式内存缓存——Java 8 + Netty，Redis 客户端零修改直连 |
| [z-camuda](https://github.com/z-opc-foundation/z-camuda) | 【已退役·归档】基于 Camunda 的工作流封装层（原 z-wf 改名，与现行 z-wf 无共同历史） |
| [z-cognition](https://github.com/z-opc-foundation/z-cognition) | 业务认知域/认知舱——术语/概念/证据链 + memory/kb/ontology 渐进式模块 |
| [z-config](https://github.com/z-opc-foundation/z-config) | 分布式配置中心 + 服务注册中心（类 Nacos）——Netty 长连接推送 + React 控制台 |
| [z-ctc](https://github.com/z-opc-foundation/z-ctc) | 4A 中心——认证/授权/账户/审计，多租户 JWT + SSO 跨子域 |
| [z-dms](https://github.com/z-opc-foundation/z-dms) | 数据源管控底座——统一登记、统一建池、统一探活、变更统一推送 |
| [z-env](https://github.com/z-opc-foundation/z-env) | 本地中间件与基础设施环境——31 个 docker-compose 场景 + make 一键运维 |
| [z-etl](https://github.com/z-opc-foundation/z-etl) | ETL 引擎——DataX 式线性作业 + Flink 式 DAG 流管道（键控状态/水位/窗口） |
| [z-ext](https://github.com/z-opc-foundation/z-ext) | Java 扩展点框架——@ExtPoint/@ExtImpl 运行期选实现，五档侵入方式 |
| [z-frontend-common](https://github.com/z-opc-foundation/z-frontend-common) | 全仓共享前端基座——AppLayout/LoginPage 等前端规范的唯一承载 |
| [z-frontend-markdown](https://github.com/z-opc-foundation/z-frontend-markdown) | Markdown 通用共享组件包（npm）——MarkdownLiveEditor/NoteMarkdown/MermaidBlock 单一真源 |
| [z-frontend-render](https://github.com/z-opc-foundation/z-frontend-render) | 平台共享前端渲染引擎——后端 schema 是唯一事实源，报表与低代码共用 |
| [z-graph](https://github.com/z-opc-foundation/z-graph) | 独立图数据库——MVCC 版本链存储 + OpenCypher 子集 + Bolt 风格协议 + React 控制台 |
| [z-gw](https://github.com/z-opc-foundation/z-gw) | 自研高性能 API 网关——Netty 手写运行时，Route/Predicate/Filter 模型可嵌 Spring Boot |
| [z-indexer](https://github.com/z-opc-foundation/z-indexer) | 代码索引服务——仓库注册表 + 定时重建索引 + 符号/调用链查询 |
| [z-introduce](https://github.com/z-opc-foundation/z-introduce) | 组织仓库展示站——全仓实测元数据汇总成可搜索静态站点 |
| [z-lc](https://github.com/z-opc-foundation/z-lc) | 低代码平台——DB 驱动通用 CRUD 引擎 + 报表/模型设计框架 |
| [z-llm-agent](https://github.com/z-opc-foundation/z-llm-agent) | CLI Agent 服务化——把本机 CLI agent（CC/Hermes/Qoder 等）暴露成 OpenAI 兼容对话 API |
| [z-llm-gateway](https://github.com/z-opc-foundation/z-llm-gateway) | LLM 网关——OpenAI/Anthropic 双协议面 + 多 vendor 凭据池、限流与 token 记账 |
| [z-llm-msghub](https://github.com/z-opc-foundation/z-llm-msghub) | msgHub 中心消息路由——WebSocket 中心枢纽 + Agent 侧客户端 SDK |
| [z-mask](https://github.com/z-opc-foundation/z-mask) | 脱敏与遮蔽引擎——按敏感命中位置遮蔽，策略即数据 |
| [z-mcp](https://github.com/z-opc-foundation/z-mcp) | MCP 服务端 + 上游聚合网关——Java 8 手写协议层，Spring Boot 自动装配 |
| [z-meta](https://github.com/z-opc-foundation/z-meta) | 元数据管理平台——租户/应用/接口清单登记 + @BizModel 业务模型采集 |
| [z-mill](https://github.com/z-opc-foundation/z-mill) | 资料磨坊——全格式文档转 Markdown/JSON/纯文本/HTML（对标 MinerU） |
| [z-mist](https://github.com/z-opc-foundation/z-mist) | KMS 分布式密钥/秘密管理平台——集中加密存储 + Netty 协议 + starter 嵌入 |
| [z-mq](https://github.com/z-opc-foundation/z-mq) | 自研分布式消息队列——NameServer+Broker+Store，形状对标 RocketMQ、自有 Netty 协议 |
| [z-msg](https://github.com/z-opc-foundation/z-msg) | 多通道消息 + WebSocket 实时下发基础设施——短信/邮件/站内信/Webhook/IM 平台 |
| [z-observer](https://github.com/z-opc-foundation/z-observer) | 可观测性域——指标/日志/链路 + 告警引擎 **（本仓）** |
| [z-opc](https://github.com/z-opc-foundation/z-opc) | 一人公司统一后端+统一前端——单 JVM all-in-one 承载协作/Agent/RPA/交易/资产等业务域与自研中间件 |
| [z-opc-foundation-lead](https://github.com/z-opc-foundation/z-opc-foundation-lead) | 组织治理大脑——事实表、强制规范、跨仓工具集、新机器恢复钥匙（本清单权威持有处） |
| [z-opcs](https://github.com/z-opc-foundation/z-opcs) | 任务撮合/服务市场——用户付费发布诉求，持有者接单完成，金额自动瓜分 |
| [z-ops](https://github.com/z-opc-foundation/z-ops) | 个人公司运维平台——部署单元/镜像构建/WebShell/K8s 巡检 + Ops Agent 执行器 |
| [z-oss](https://github.com/z-opc-foundation/z-oss) | 对象存储服务——自持 local 后端 + aliyun/s3 对接层，starter 注入 OssTemplate |
| [z-product](https://github.com/z-opc-foundation/z-product) | 产品设计信息汇总仓（开发域 PM 工件） |
| [z-qa](https://github.com/z-opc-foundation/z-qa) | 步骤式 API 链路测试平台——用例/计划/执行/报告/质量看板（对标 TestRail·MeterSphere） |
| [z-rpa](https://github.com/z-opc-foundation/z-rpa) | RPA 产品皮——应用市场/需求中心（执行归 z-script，触发归 z-schedule） |
| [z-rpc](https://github.com/z-opc-foundation/z-rpc) | Java 8 + Netty RPC 框架——微内核 + SPI 插件化，形状参照 Dubbo/gRPC |
| [z-schedule](https://github.com/z-opc-foundation/z-schedule) | 分布式任务调度中枢（对标 XXL-Job）——选主 + 时间环排期，starter 嵌入宿主 |
| [z-script](https://github.com/z-opc-foundation/z-script) | 多 DSL 脚本平台 + Mock 平台——沙箱执行/版本灰度/可注册 MCP Tool，starter 内嵌 |
| [z-security](https://github.com/z-opc-foundation/z-security) | 数据安全域编排层——场景→策略→识别→脱敏→水印→审计 四段流水线编排 |
| [z-sense](https://github.com/z-opc-foundation/z-sense) | 敏感识别与分类分级引擎——数据安全线底座（规则目录 + 可插拔识别器 + 分类分级） |
| [z-skill](https://github.com/z-opc-foundation/z-skill) | Skill 平台聚合器——归一化各家 Skill 形状，再按各家 API 形状发回 |
| [z-surl](https://github.com/z-opc-foundation/z-surl) | 短网址服务——短码生成/解析/命中统计，starter 一行集成 |
| [z-task](https://github.com/z-opc-foundation/z-task) | 任务协作平台——项目空间/看板/任务卡片（看板范式，对标 Trello·Teambition） |
| [z-team](https://github.com/z-opc-foundation/z-team) | 数字员工协同域——全栈独立仓，自 z-opc 毕业 |
| [z-trade](https://github.com/z-opc-foundation/z-trade) | 交易域独立仓——自 z-opc reactor 迁出 |
| [z-util](https://github.com/z-opc-foundation/z-util) | 底层 Java 工具库——89 个模块 POM，Java 8/17 口径，按需引用按需升级 |
| [z-vector](https://github.com/z-opc-foundation/z-vector) | 自研 Java 向量数据库——嵌入式+可独立部署，ANN+Payload 过滤，兼容 Qdrant REST |
| [z-voice-demo](https://github.com/z-opc-foundation/z-voice-demo) | 语音链路 demo——流式识别→kernel LLM→流式 TTS→可打断 的契约验证实现 |
| [z-watermark](https://github.com/z-opc-foundation/z-watermark) | 水印引擎——显式文字/图片隐写/盲水印，三种技术成对「打+验」 |
| [z-webide](https://github.com/z-opc-foundation/z-webide) | 企业级 WebIDE 后端——Git 仓/共享目录秒变 code-server 实例，全生命周期管理 |
| [z-wf](https://github.com/z-opc-foundation/z-wf) | 自研流程引擎——不依赖 Camunda/Flowable，定义层与运行时全自研 |

**已退役仓**（迁至 [z-trash](https://github.com/z-trash) 组织，archived 只读）：

| 仓 | 说明 |
|---|---|
| [z-agent-proxy](https://github.com/z-trash/z-agent-proxy) | 三方 coding agent 适配代理（Aider/Cursor → kernel Agent SPI），2026-10-26 退役 |
| [z-kb](https://github.com/z-trash/z-kb) | 自研知识库系统（对标 LightRAG/RAGFlow），2026-11 并入 z-cognition 认知舱后退役 |
| [z-ontology](https://github.com/z-trash/z-ontology) | 本体层（TBox/ABox/RDFS 推理），并入 z-cognition 后退役 |

另有 [z-report](https://github.com/z-opc-foundation/z-report)（报表域，2026-10-11 撤销并入 z-lc（远端已删除））。

总清单权威持有处在 [z-opc-foundation-lead](https://github.com/z-opc-foundation/z-opc-foundation-lead) `008_组织规范/005_兄弟仓清单.md`。
