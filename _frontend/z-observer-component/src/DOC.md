# z-observer-component

z-observer（可观测性）跨仓唯一前端面：把「概览 / 指标查询 / 日志检索 / 链路追踪 / 告警」
这一组可观测性页面收敛成一个可复用的 React 组件包，由 `z-observer-suit` 独立壳或平台主壳挂载。

- **npm 包名**：`@yuku123/z-observer-component`
- **宿主接入**：`@yuku123/z-observer-component/pages`（manifest + 请求层），主壳可只取 `ComponentDocPage`
- **后端**：z-observer 收端的 `/api/**`（日志 / 链路 / 告警 / 指标）
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + dayjs + vite
  （library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

路由前缀 `/z-observer`，菜单与路由都在 `src/pages-manifest.jsx` 里声明，宿主读数组自己组装。
前缀的作用是**把收端 REST 与 SPA 路由彻底隔开** —— 少了前缀，浏览器直接刷新
`/z-observer/logs` 拿到的是 JSON 而不是页面。

| 页面 | 路由（相对 `/z-observer/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 概览 | `home` | `pages/HomePage.jsx` | 日志 / 链路 / 告警三路计数 + 各功能入口 |
| 指标 | `metrics` | `pages/MetricPage.jsx` | Prometheus 即时查询（PromQL） |
| 日志 | `logs` | `pages/LogSearchPage.jsx` | 日志检索（关键词 / 时间范围 / 级别） |
| 链路 | `traces` | `pages/TracePage.jsx` | 最近链路列表 + 选中链路的 span 树 |
| 告警 | `alerts` | `pages/AlertPage.jsx` | 告警列表 + 静默 / 抑制 |
| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

⚠ **这个包没有登录页**：`src/pages/` 下只有上表这几个页面，manifest 也没有导出 `LoginPage`
（其它包都有）。收端自己的鉴权方式由宿主决定。

## 二、导出面

```js
// 主入口（. 桶）：整个 pages.js 的转发
import {menuItems, routes, appMeta, HomePage, LogSearchPage, TracePage, AlertPage, MetricPage,
        logApi, traceApi, alertApi, metricApi, ComponentDocPage} from '@yuku123/z-observer-component'

// 宿主组装面（./pages 桶）：同一批名字
import {menuItems, routes, appMeta, ComponentDocPage} from '@yuku123/z-observer-component/pages'

// 组件文档页（主壳接线用，名字全仓统一）
import {ComponentDocPage} from '@yuku123/z-observer-component'
```

主入口是 `export * from './pages.js'`，`pages.js` 同时转发 manifest 的页面导出与
`services/api.js` 的四个 API 对象 ⇒ 文档页只要在 manifest 里导出并加进 `pages.js`，
主入口就会自动带上它。

## 三、API 端点

请求实例是本包自建的 axios 实例（`src/services/api.js`），超时 15s，**不带 baseURL**
⇒ 下面的路径就是浏览器实际请求的路径（都自带 `/api` 前缀）。
响应拦截器直接返回 `r.data`（收端返的是**裸 JSON**，不是 z-opc 那种 `{code, data, message}` 信封），
失败时把后端的 `{status, error}` 里的 `error` 原文抛出来。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/logs` | 日志检索（query：关键词 / 时间范围 / 级别等） |
| GET | `/api/logs/status` | 日志通道状态 |
| GET | `/api/traces` | 最近链路（query：`limit`，默认 20） |
| GET | `/api/traces/{traceId}` | 单条链路的 span 树 |
| GET | `/api/alerts` | 告警列表 |
| POST | `/api/silences` | 新建静默 |
| POST | `/api/inhibits` | 新建抑制 |

## 四、页面说明

### 4.1 概览

并行拉告警、日志、链路三路数据，给出计数与入口卡片。任一路失败用基座 `ErrorState` 显示。

### 4.2 指标

Prometheus **即时查询**（PromQL），后端直读 Prometheus。
⚠ 本页**不存任何指标**（D2 过渡方案 B）—— 查询历史、看板都不在这个包里。

### 4.3 日志

日志检索。关键词用空格分隔是「且」关系；中文按 unigram + bigram 建索引，**无需分词**。

### 4.4 链路

左边最近链路列表，右边把选中的那条画成树。
树用后端返回的父子结构**直接渲染**，不在前端重算 span 耗时 ——
⚠ 后端对不存在的 traceId 返 **404**（不是空树），「没采到」和「采到但是空」必须分得开。

### 4.5 告警

告警列表 + 新建静默 / 新建抑制（时间范围用 dayjs 的 DatePicker）。

### 4.6 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| 请求前缀 | — | axios 实例**不设 baseURL**，路径自带 `/api` |
| 请求超时 | `15000` ms | 自建实例的 `timeout` |
| 最近链路条数 | `20` | `traceApi.recent` 的默认 `limit` |

## 六、已知限制

- **请求层没走基座**：`src/services/api.js` 自己 `axios.create`，**没有用基座的
  `createRequest`** ⇒ 不注入 `Authorization` 头、不读 token 存储键、401 不会跳登录。
  这是有意为之：收端返回裸 JSON，走基座的统一解包反而要额外解包。
- **没有登录页**：本包不含 `LoginPage`，收端的鉴权由宿主决定。
- **指标页只做即时查询**：没有范围查询、没有看板、没有查询历史 ——
  后端直读 Prometheus 是 D2 过渡方案 B。
- **链路页不做 span 耗时聚合**：直接渲染后端给的父子结构。
- **告警的静默 / 抑制是 POST 建**，本页没有删除 / 撤销入口。
- 静态 `message`（antd 6 下会告警，规范要求改 `App.useApp()`）。
- z-observer 组件包**没有**被 z-opc 主壳的 `domainRoutes.ts` 注册，也没被 z-team 的
  `SystemShell` 引用，目前只有 `z-observer-suit` 独立壳在用
  ⇒ `/z-observer/docs` 在平台主壳里**没有可达路径**。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.1 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |
| 0.1.1 | 首版：概览 / 指标 / 日志 / 链路 / 告警页面集 + manifest + 自建 request |
