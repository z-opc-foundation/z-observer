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
- 去噪三件套：分组 group_by / 抑制 inhibit / 静默 silence。
  静默只压通知不改状态机；抑制需要成对写。
- 通知出口抽象成 `MessageChannel`，形状对齐 z-msg 渠道屏蔽层。
  **本仓不实现任何具体渠道**：钉钉/短信的凭据一律运行时从 z-mist 取，
  z-observer 里不留明文。默认挂一个写日志的渠道兜底 —— 渠道没配好时告警至少
  还留在收端日志里，不会因为「没渠道」整条消失。

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

## 本地起

```bash
mvn clean install

# 收端（默认 8080，采集端口 14300）
java -jar z-observer-admin/target/z-observer-admin.jar \
  --z.observer.prometheus-url=http://127.0.0.1:9090 \
  --z.observer.scrape-urls=http://127.0.0.1:9100/metrics

# 看板
cd _frontend/z-observer-component && npm install && npm run build
cd ../z-observer-suit && npm install
LOCAL_SIBLINGS=1 npm run dev     # 3020，代理 /api/ 到收端
```

配置项一律无凭据字段：真值唯一居所是 `z-opc-foundation-lead/004_重要秘钥/`。

## 当前阶段

M1（指标 + 告警最小闭环）已闭环。M2（日志前端收口进 z-opc）/ M3（traceparent
透传三处缝）按需求卡 [007](https://github.com/z-opc-foundation/z-opc-foundation-lead/blob/main/002_项目需求/001_特性需求/007_z-observer_可观测性域/README.md)
的分阶段路线推进。
