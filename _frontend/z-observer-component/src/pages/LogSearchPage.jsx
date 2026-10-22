import { useEffect, useState } from 'react'
import { Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { EmptyState, ErrorState, SearchInput, TableToolbar } from '@yuku123/z-frontend-common/ui'
import { logApi } from '../services/api.js'

const LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR']

const LEVEL_COLOR = {
    ERROR: 'red',
    WARN: 'orange',
    INFO: 'blue',
    DEBUG: 'default',
    TRACE: 'default',
}

/**
 * 日志检索页。
 *
 * <p>时间窗按「最近 N 分钟」这种相对口径给默认值而不是绝对时间戳：
 * 值班的人打开这个页面的意图永远是「刚才发生了什么」。
 */
export default function LogSearchPage() {
    const [keyword, setKeyword] = useState('')
    const [level, setLevel] = useState(undefined)
    const [traceId, setTraceId] = useState('')
    const [minutes, setMinutes] = useState(15)
    const [rows, setRows] = useState([])
    const [status, setStatus] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    async function search(kwOverride) {
        setLoading(true)
        setError(null)
        try {
            const to = Date.now()
            const from = to - minutes * 60 * 1000
            const params = { limit: 200 }
            // kwOverride 让 Enter 能用「输入框此刻的关键词」查询，不必等防抖回写 state。
            // 用 typeof 兜一层：antd 的 onPressEnter / onClick 会把事件对象当第一个参数传进来。
            const kw = typeof kwOverride === 'string' ? kwOverride : keyword
            if (kw) params.q = kw
            if (level) params.level = level
            if (traceId) params.traceId = traceId
            if (minutes > 0) {
                params.from = from
                params.to = to
            }
            const [data, st] = await Promise.all([logApi.search(params), logApi.status()])
            setRows(Array.isArray(data) ? data : [])
            setStatus(st)
        } catch (e) {
            setError(e.message)
            setRows([])
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        search()
    }, [])

    return (
        <Card
            title="日志检索"
            extra={
                <Space>
                    <Button icon={<ReloadOutlined />} onClick={() => search()} loading={loading}>
                        刷新
                    </Button>
                </Space>
            }
        >
            <TableToolbar
                left={
                    <>
                        <SearchInput
                            style={{ width: 280 }}
                            placeholder="关键词，空格分隔为且关系"
                            value={keyword}
                            onChange={setKeyword}
                            onSearch={(v) => search(v)}
                        />
                        <Select
                            style={{ width: 120 }}
                            placeholder="级别"
                            allowClear
                            value={level}
                            onChange={setLevel}
                            options={LEVELS.map((l) => ({ label: l, value: l }))}
                        />
                        <Input
                            style={{ width: 240 }}
                            placeholder="traceId（按链路聚类）"
                            value={traceId}
                            onChange={(e) => setTraceId(e.target.value)}
                            onPressEnter={() => search()}
                            allowClear
                        />
                        <Select
                            style={{ width: 140 }}
                            value={minutes}
                            onChange={setMinutes}
                            options={[5, 15, 60, 360, 1440].map((m) => ({
                                label: `最近 ${m} 分钟`,
                                value: m,
                            }))}
                        />
                    </>
                }
                right={
                    <Button type="primary" icon={<SearchOutlined />} onClick={() => search()} loading={loading}>
                        查询
                    </Button>
                }
            />

            {error && (
                <div style={{ marginBottom: 12 }}>
                    <ErrorState error={error} title="查询失败" onRetry={() => search()} />
                </div>
            )}

            <Table
                rowKey={(r, i) => `${r.timestampMillis}-${i}`}
                size="small"
                loading={loading}
                dataSource={rows}
                // 分页按 lead 008 §2 规则 2 补齐四件套（原来只有 pageSize + showSizeChanger，
                // 缺 pageSizeOptions / showTotal）。
                // 【为什么是端侧分页】这页的检索是**服务端查询**：logApi.search(params) → GET /api/logs，
                // 关键词 / 级别 / traceId / 时间窗都由后端过滤，本页一次取回 limit=200 条
                // （后端 LogQuery 的硬上限是 1000）；后端**没有 offset / total 参数**
                // ⇒ 翻页只能在这 200 条已取回的窗口里切，改成 current/onChange 的服务端分页
                // 会拿不到后端没有的数据。
                // 【为什么不接基座 ListTable】页面上方 TableToolbar 里已有自己的查询控件
                // （关键词 / 级别 / traceId / 时间窗 + 查询按钮），再挂 searchKeys 会出现两个搜索框；
                // 这里保留 antd 原生 Table，空态仍用基座 EmptyState。
                pagination={{
                    pageSize: 50,
                    showSizeChanger: true,
                    pageSizeOptions: ['10', '20', '50', '100'],
                    showTotal: (total) => `共 ${total} 条`,
                }}
                locale={{
                    emptyText: (
                        <EmptyState
                            title="没有匹配的日志"
                            description="放宽关键词或扩大时间窗再查一次"
                            size="sm"
                        />
                    ),
                }}
                columns={[
                    {
                        title: '时间',
                        dataIndex: 'timestampMillis',
                        width: 170,
                        render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss.SSS') : '-'),
                    },
                    {
                        title: '级别',
                        dataIndex: 'level',
                        width: 90,
                        render: (v) => <Tag color={LEVEL_COLOR[v]}>{v}</Tag>,
                    },
                    { title: '线程', dataIndex: 'thread', width: 120 },
                    { title: 'logger', dataIndex: 'logger', width: 220, ellipsis: true },
                    { title: '消息', dataIndex: 'message', ellipsis: true },
                    {
                        title: 'traceId',
                        dataIndex: 'traceId',
                        width: 200,
                        ellipsis: true,
                        render: (v) =>
                            v ? <Typography.Text copyable={{ text: v }}>{v}</Typography.Text> : '-',
                    },
                ]}
            />

            {status && (
                <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12 }}>
                    在库 {status.liveLogs} 条，累计淘汰 {status.evictedLogs} 条
                </Typography.Text>
            )}
        </Card>
    )
}