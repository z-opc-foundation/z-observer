import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, DatePicker, Input, Row, Space, Statistic, Table, Tag } from 'antd'
import dayjs from 'dayjs'
import { DEFAULT_PAGINATION, EmptyState, ErrorState } from '@yuku123/z-frontend-common/ui'
import { alertApi } from '../services/api.js'

const SEVERITY_COLOR = { critical: 'red', warning: 'orange', info: 'blue' }
const STATE_COLOR = { FIRING: 'red', PENDING: 'orange', OK: 'green', RESOLVED: 'green' }

function toDateTime(value) {
    return value ? value.valueOf() : undefined
}

/**
 * 告警页。
 *
 * <p>顶部四个统计直接取后端计数：通知失败数在这里必须显式露出，
 * 否则「以为发出去了」这个最坏状态在界面上完全看不出来。
 */
export default function AlertPage() {
    const [data, setData] = useState(null)
    const [error, setError] = useState(null)
    const [loading, setLoading] = useState(false)

    const [silenceMatchers, setSilenceMatchers] = useState('')
    const [silenceRange, setSilenceRange] = useState(null)
    const [silenceReason, setSilenceReason] = useState('')
    const [inhibitExpr, setInhibitExpr] = useState('')
    const [actionMsg, setActionMsg] = useState(null)

    async function load() {
        setLoading(true)
        setError(null)
        try {
            setData(await alertApi.list())
        } catch (e) {
            setError(e.message)
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        load()
    }, [])

    async function submitSilence() {
        setActionMsg(null)
        if (!silenceReason) {
            setActionMsg('静默必须填原因 —— 事后要能回答「为什么这段时间没人收到告警」')
            return
        }
        const range = silenceRange || [dayjs(), dayjs().add(1, 'hour')]
        try {
            await alertApi.addSilence({
                id: `s-${Date.now()}`,
                matchers: silenceMatchers
                    .split(/[\s,]+/)
                    .filter(Boolean),
                startMillis: toDateTime(range[0]),
                endMillis: toDateTime(range[1]),
                reason: silenceReason,
                createdBy: 'console',
            })
            setActionMsg('静默已生效')
            setSilenceMatchers('')
            setSilenceReason('')
            setSilenceRange(null)
            load()
        } catch (e) {
            setActionMsg(`加静默失败：${e.message}`)
        }
    }

    async function submitInhibit() {
        setActionMsg(null)
        const parts = inhibitExpr.split(/[\s,]+/).filter(Boolean)
        if (parts.length < 2) {
            setActionMsg('抑制规则填两个条件：source=... target=...（如 cluster_down target=api_high_error）')
            return
        }
        const source = parts.find((p) => p.startsWith('source='))
        const target = parts.find((p) => p.startsWith('target='))
        const equal = parts.find((p) => p.startsWith('equal='))
        if (!source || !target) {
            setActionMsg('抑制规则必须有 source= 与 target=')
            return
        }
        try {
            await alertApi.addInhibit({
                id: `i-${Date.now()}`,
                sourceRuleId: source.slice('source='.length),
                targetRuleId: target.slice('target='.length),
                equalLabel: equal ? equal.slice('equal='.length) : '',
            })
            setActionMsg('抑制规则已生效')
            load()
        } catch (e) {
            setActionMsg(`加抑制失败：${e.message}`)
        }
    }

    return (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Row gutter={16}>
                <Col span={6}>
                    <Card>
                        <Statistic title="活动告警" value={data?.active?.length ?? 0} />
                    </Card>
                </Col>
                <Col span={6}>
                    <Card>
                        <Statistic title="已发通知" value={data?.notificationsSent ?? 0} />
                    </Card>
                </Col>
                <Col span={6}>
                    <Card>
                        <Statistic
                            title="通知失败"
                            value={data?.notificationsFailed ?? 0}
                            valueStyle={(data?.notificationsFailed ?? 0) > 0 ? { color: '#cf1322' } : undefined}
                        />
                    </Card>
                </Col>
                <Col span={6}>
                    <Card>
                        <Statistic
                            title="评估失败"
                            value={data?.evaluateErrorCount ?? 0}
                            valueStyle={(data?.evaluateErrorCount ?? 0) > 0 ? { color: '#cf1322' } : undefined}
                        />
                    </Card>
                </Col>
            </Row>

            {error && (
                <div style={{ marginBottom: 16 }}>
                    <ErrorState error={error} title="读告警失败" onRetry={load} />
                </div>
            )}

            <Card title="活动告警">
                <Table
                    rowKey={(r, i) => `${r.ruleId}-${i}`}
                    size="small"
                    loading={loading}
                    dataSource={data?.active ?? []}
                    pagination={DEFAULT_PAGINATION}
                    locale={{ emptyText: <EmptyState title="当前没有活动告警" description="规则触发后活动告警会出现在这里" size="sm" /> }}
                    columns={[
                        { title: '规则', dataIndex: 'ruleName' },
                        {
                            title: '级别',
                            dataIndex: 'severity',
                            render: (v) => <Tag color={SEVERITY_COLOR[v]}>{v}</Tag>,
                        },
                        {
                            title: '状态',
                            dataIndex: 'state',
                            render: (v) => <Tag color={STATE_COLOR[v]}>{v}</Tag>,
                        },
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
                        { title: '取值', dataIndex: 'value', render: (v) => v ?? '-' },
                        {
                            title: '开始于',
                            dataIndex: 'activeSinceMillis',
                            render: (v) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-'),
                        },
                    ]}
                />
            </Card>

            <Card title="去噪操作">
                <Space direction="vertical" style={{ width: '100%' }}>
                    <TypographyHint />
                    <Space wrap>
                        <Input
                            style={{ width: 420 }}
                            placeholder="条件，空格分隔：app=z-gw 或 cluster=c1（留空=全员静默）"
                            value={silenceMatchers}
                            onChange={(e) => setSilenceMatchers(e.target.value)}
                        />
                        <DatePicker.RangePicker
                            showTime
                            value={silenceRange}
                            onChange={setSilenceRange}
                        />
                        <Input
                            style={{ width: 260 }}
                            placeholder="原因（必填）"
                            value={silenceReason}
                            onChange={(e) => setSilenceReason(e.target.value)}
                        />
                        <Button type="primary" onClick={submitSilence}>加静默</Button>
                    </Space>
                    <Space.Compact style={{ width: '100%' }}>
                        <Input
                            style={{ width: 460 }}
                            placeholder="抑制规则：source=cluster_down target=api_high_error equal=cluster"
                            value={inhibitExpr}
                            onChange={(e) => setInhibitExpr(e.target.value)}
                        />
                        <Button onClick={submitInhibit}>加抑制</Button>
                    </Space.Compact>
                    {actionMsg && <Alert type="info" message={actionMsg} showIcon />}
                </Space>
            </Card>
        </Space>
    )
}

function TypographyHint() {
    return (
        <Alert
            type="info"
            showIcon
            message="静默只压通知不改状态机；抑制需要成对写，source 与 target 不能是同一条规则"
        />
    )
}