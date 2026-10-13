import {
    AlertOutlined,
    DashboardOutlined,
    FieldTimeOutlined,
    FileSearchOutlined,
    LineChartOutlined,
} from '@ant-design/icons'
import LogSearchPage from './pages/LogSearchPage.jsx'
import TracePage from './pages/TracePage.jsx'
import AlertPage from './pages/AlertPage.jsx'
import MetricPage from './pages/MetricPage.jsx'
import HomePage from './pages/HomePage.jsx'

/**
 * 菜单 + 路由清单（lead 008 §10/§14）。App 壳在 suit 侧组装。
 *
 * <p>路径统一带 /z-observer 前缀：将来并进 z-opc 运维中心子区时，
 * 前缀就是各域互不打架的那道缝。
 */
export const appMeta = { title: 'z-observer 可观测性', short: 'z-observer' }

export const menuItems = [
    { key: '/z-observer/home', label: '概览', icon: <DashboardOutlined /> },
    { key: '/z-observer/metrics', label: '指标', icon: <LineChartOutlined /> },
    { key: '/z-observer/logs', label: '日志', icon: <FileSearchOutlined /> },
    { key: '/z-observer/traces', label: '链路', icon: <FieldTimeOutlined /> },
    { key: '/z-observer/alerts', label: '告警', icon: <AlertOutlined /> },
]

export const routes = [
    { path: '/z-observer/home', Component: HomePage },
    { path: '/z-observer/metrics', Component: MetricPage },
    { path: '/z-observer/logs', Component: LogSearchPage },
    { path: '/z-observer/traces', Component: TracePage },
    { path: '/z-observer/alerts', Component: AlertPage },
]

export { default as HomePage } from './pages/HomePage.jsx'
export { default as LogSearchPage } from './pages/LogSearchPage.jsx'
export { default as TracePage } from './pages/TracePage.jsx'
export { default as AlertPage } from './pages/AlertPage.jsx'
export { default as MetricPage } from './pages/MetricPage.jsx'