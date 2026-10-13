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

const http = axios.create({ timeout: 15000 })

http.interceptors.response.use(
    (r) => r.data,
    (e) => {
        // 收端把失败包成 {status, error}，这里原样抛出去让页面显示后端的真实原因，
        // 而不是 axios 那句 "Request failed with status code 502"
        const message = e?.response?.data?.error || e.message || '请求失败'
        return Promise.reject(new Error(message))
    },
)

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