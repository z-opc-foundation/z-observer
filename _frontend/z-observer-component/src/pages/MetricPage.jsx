import { useState } from 'react'
import { Alert, Button, Card, Input, Space, Table, Tag, Typography } from 'antd'
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
        <Card title="指标即时查询">
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
                dataSource={rows}
                pagination={{ pageSize: 50 }}
                locale={{
                    emptyText: queried
                        ? '没有匹配的时序（目标可能已下线）'
                        : '输入 PromQL 后查询',
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