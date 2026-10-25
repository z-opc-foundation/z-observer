import { useMemo, useState } from 'react'
import { Alert, Button, Card, Input, Space, Table, Tag, Typography } from 'antd'
import { EmptyState } from '@yuku123/z-frontend-common/ui'
import { metricApi } from '../services/api.js'

/**
 * 指标即时查询页。
 *
 * <p>后端直读 Prometheus（D2 过渡方案 B），本页<b>不存任何指标</b>。
 * 「查不到」显示成空表而不是 0：进程挂了时序列会从 Prometheus 里消失，
 * 显示成 0 会让值班的人以为「它还在，只是没流量」。
 */
export default function MetricPage() {
    const [promql, setPromql] = useState('up')
    const [rows, setRows] = useState([])
    const [error, setError] = useState(null)
    const [loading, setLoading] = useState(false)
    const [queried, setQueried] = useState(false)

    // ── 时序结果查询过滤（lead 008 §2 规则 3，端侧过滤） ──
    // 过滤字段取本表 columns 里真实存在的 dataIndex：labels / value。
    // labels 是对象，拼成 "k=v" 文本再匹配（直接 ${labels} 只会得到 [object Object]）。
    // 【与上方 PromQL 输入框的区别】PromQL 是服务端查询（决定查哪批时序），
    // 这里这个框只过滤**已经取回**的这批时序，两者不是一回事，所以不冲突。
    const [keyword, setKeyword] = useState('')
    const filteredRows = useMemo(() => {
        const kw = keyword.trim().toLowerCase()
        if (!kw) return rows
        return rows.filter((r) => {
            const labelText = Object.entries(r.labels || {})
                .map(([k, v]) => `${k}=${v}`)
                .join(' ')
            return `${labelText} ${r.value ?? ''}`.toLowerCase().includes(kw)
        })
    }, [rows, keyword])

    async function run() {
        setLoading(true)
        setError(null)
        try {
            const data = await metricApi.instant(promql)
            setRows(Array.isArray(data) ? data : [])
            setQueried(true)
        } catch (e) {
            setError(e.message)
            setRows([])
            setQueried(true)
        } finally {
            setLoading(false)
        }
    }

    return (
        <Card
            title="指标即时查询"
            extra={
                <Input.Search
                    allowClear
                    placeholder="按标签 / 取值过滤"
                    style={{ width: 240 }}
                    onChange={(e) => setKeyword(e.target.value)}
                />
            }
        >
            <Space.Compact style={{ width: '100%', marginBottom: 16 }}>
                <Input
                    style={{ width: 520 }}
                    value={promql}
                    onChange={(e) => setPromql(e.target.value)}
                    onPressEnter={run}
                    placeholder={'PromQL，例如 up{job="z-gw"}'}
                />
                <Button type="primary" onClick={run} loading={loading}>查询</Button>
            </Space.Compact>

            {error && (
                <Alert
                    type="warning"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message="指标源不可达或表达式有误"
                    description={error}
                />
            )}

            <Table
                rowKey={(r, i) => r.seriesKey || String(i)}
                size="small"
                loading={loading}
                dataSource={filteredRows}
                // 分页按 lead 008 §2 规则 2 补齐四件套（原来只有 pageSize，
                // 缺 showSizeChanger / pageSizeOptions / showTotal）。
                // 【为什么是端侧分页】这页的查询是**服务端查询**：metricApi.instant(promql)
                // → GET /api/metrics/query，PromQL 在后端 Prometheus 上算完，返回命中的时序全量；
                // 后端**没有 offset / total 参数**（AlertQueryController.query 只接 promql + time）
                // ⇒ 翻页只能在这批已取回的时序里切。
                // 【为什么不接基座 ListTable】页面上方已有 PromQL 输入框 + 查询按钮，
                // ListTable 自带一层 Card 会变成卡片套卡片；这里保留 antd 原生 Table，
                // 空态仍用基座 EmptyState，查询过滤按 §2 规则 3 用 Card extra 里的
                // Input.Search（端侧过滤已取回的时序，与服务端 PromQL 查询不是一回事）。
                pagination={{
                    pageSize: 50,
                    showSizeChanger: true,
                    pageSizeOptions: ['10', '20', '50', '100'],
                    showTotal: (total) => `共 ${total} 条`,
                }}
                locale={{
                    emptyText: queried
                        ? <EmptyState title="没有匹配的时序" description="目标可能已下线，换个 PromQL 再查一次" size="sm" />
                        : <EmptyState title="尚未查询" description="输入 PromQL 后查询" size="sm" />,
                }}
                columns={[
                    {
                        title: '标签',
                        dataIndex: 'labels',
                        render: (labels) =>
                            Object.entries(labels || {}).map(([k, v]) => (
                                <Tag key={k}>
                                    {k}={v}
                                </Tag>
                            )),
                    },
                    {
                        title: '取值',
                        dataIndex: 'value',
                        width: 160,
                        render: (v) => (Number.isNaN(v) ? <Tag>NaN</Tag> : String(v)),
                    },
                    {
                        title: '采样时间',
                        dataIndex: 'timestampMillis',
                        width: 200,
                        render: (v) => new Date(v).toLocaleString(),
                    },
                ]}
            />

            <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
                M1 期间指标读的是 z-env 已有的 Prometheus 本体，z-observer 自己不落时序存储；
                与第三方 Prometheus / Grafana 并存是有意为之的过渡形态。
            </Typography.Paragraph>
        </Card>
    )
}