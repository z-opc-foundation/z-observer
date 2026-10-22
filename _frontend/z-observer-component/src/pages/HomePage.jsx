import { useEffect, useState } from 'react'
import { Card, Col, Row, Statistic } from 'antd'
import { Link } from 'react-router-dom'
import { ErrorState } from '@yuku123/z-frontend-common/ui'
import { alertApi, logApi, traceApi } from '../services/api.js'

/**
 * 概览页。
 *
 * <p>每块数字都带「去看」的入口：值班的人看到「通知失败 3」之后要能一步跳到告警页，
 * 而不是自己在菜单里找。数字单独摆着没用。
 */
export default function HomePage() {
    const [stats, setStats] = useState(null)
    const [error, setError] = useState(null)

    // 抽成 load 是为了给 ErrorState 提供重试入口（原来失败只在控制台留一句）
    const load = async () => {
        try {
            const [logs, traces, alerts] = await Promise.all([logApi.status(), traceApi.recent(1), alertApi.list()])
            setStats({ logs, traces, alerts })
            setError(null)
        } catch (e) {
            setError(e.message)
        }
    }

    useEffect(() => {
        load()
    }, [])

    return (
        <Card title="z-observer 概览">
            {error && (
                <div style={{ marginBottom: 16 }}>
                    <ErrorState error={error} title="读状态失败" onRetry={load} />
                </div>
            )}
            <Row gutter={16}>
                <Col span={6}>
                    <Card size="small">
                        <Statistic
                            title={<Link to="/z-observer/logs">在库日志</Link>}
                            value={stats?.logs?.liveLogs ?? '-'}
                        />
                        <div style={{ color: '#999' }}>累计淘汰 {stats?.logs?.evictedLogs ?? '-'}</div>
                    </Card>
                </Col>
                <Col span={6}>
                    <Card size="small">
                        <Statistic
                            title={<Link to="/z-observer/traces">在库链路</Link>}
                            value={stats?.traces?.traceCount ?? '-'}
                        />
                        <div style={{ color: '#999' }}>累计淘汰 {stats?.traces?.evictedTraces ?? '-'}</div>
                    </Card>
                </Col>
                <Col span={6}>
                    <Card size="small">
                        <Statistic
                            title={<Link to="/z-observer/alerts">活动告警</Link>}
                            value={stats?.alerts?.active?.length ?? '-'}
                            valueStyle={
                                (stats?.alerts?.active?.length ?? 0) > 0 ? { color: '#cf1322' } : undefined
                            }
                        />
                    </Card>
                </Col>
                <Col span={6}>
                    <Card size="small">
                        <Statistic
                            title="通知失败"
                            value={stats?.alerts?.notificationsFailed ?? '-'}
                            valueStyle={
                                (stats?.alerts?.notificationsFailed ?? 0) > 0 ? { color: '#cf1322' } : undefined
                            }
                        />
                        <div style={{ color: '#999' }}>被静默压住 {stats?.alerts?.notificationsSilenced ?? '-'}</div>
                    </Card>
                </Col>
            </Row>
        </Card>
    )
}