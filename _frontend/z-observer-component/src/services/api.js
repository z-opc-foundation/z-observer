/**
 * z-observer 收端查询面的 API 客户端。
 *
 * <p>所有路径都带 /api/ 前缀：收端的 REST 与 SPA 路由靠这个前缀彻底隔开。
 * 少了前缀的话，浏览器直接刷新 /z-observer/logs 拿到的是 JSON 而不是页面。
 *
 * <p>axios 直接用，不用 createRequest 工厂：收端返回的是裸 JSON（不是 z-opc 那种
 * Result 信封），走统一拦截器反而要额外解包。
 */
import axios from 'axios'
import { readAdminToken } from './adminCredentials.js'

const http = axios.create({ timeout: 15000, auth: null })

http.interceptors.request.use(config => {
    // Do not inherit a host's global axios Authorization default. Only this
    // instance's private probe may supply a candidate instead of the saved token.
    if (!config.observerTokenProbe) {
        const token = readAdminToken()
        if (token) config.headers.set('Authorization', `Bearer ${token}`)
        else config.headers.delete('Authorization')
    }
    return config
})

http.interceptors.response.use(
    (r) => r.data,
    (e) => {
        // Never expose axios config/headers or a server-reflected credential to UI.
        // This instance neither redirects the host nor clears any shared storage.
        const status = e?.response?.status
        const message = status === 401
            ? '管理令牌无效或已过期，请重新连接'
            : status >= 500
                ? '服务暂时不可用，请稍后重试'
                : status ? `请求失败（HTTP ${status}），请重试` : '网络连接失败，请检查网络后重试'
        const error = new Error(message)
        error.status = status
        error.code = e?.code === 'ERR_CANCELED' ? 'ERR_CANCELED' : undefined
        return Promise.reject(error)
    },
)

// Not a login API: verifies the candidate with the existing read-only endpoint.
export async function verifyAdminToken(token, signal) {
    const data = await http.get('/api/logs/status', {
        headers: { Authorization: `Bearer ${token}` },
        observerTokenProbe: true,
        signal,
    })
    if (!data || !Number.isFinite(data.liveLogs) || !Number.isFinite(data.evictedLogs)) {
        throw new Error('服务响应不符合日志状态格式，请检查连接地址后重试')
    }
}

export const logApi = {
    /** 关键词用空格分隔是「且」关系；中文按 unigram+bigram 建索引，无需分词 */
    search: (params) => http.get('/api/logs', { params }),
    status: () => http.get('/api/logs/status'),
}

export const traceApi = {
    recent: (limit = 20) => http.get('/api/traces', { params: { limit } }),
    /** 后端对不存在的 traceId 返 404，不是空树 —— 「没采到」和「采到但是空」要分得开 */
    tree: (traceId) => http.get(`/api/traces/${encodeURIComponent(traceId)}`),
}

export const alertApi = {
    list: () => http.get('/api/alerts'),
    addSilence: (payload) => http.post('/api/silences', payload),
    addInhibit: (payload) => http.post('/api/inhibits', payload),
}

export const metricApi = {
    instant: (promql, time) => http.get('/api/metrics/query', { params: { promql, time } }),
    range: (promql, from, to, step) =>
        http.get('/api/metrics/query-range', { params: { promql, from, to, step } }),
}