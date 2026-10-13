import { useEffect, useState } from 'react'
import { Card, Col, Row, Statistic, Alert } from 'antd'
import { Link } from 'react-router-dom'
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

    useEffect(() => {
        Promise.all([logApi.status(), traceApi.recent(1), alertApi.list()])
            .then(([logs, traces, alerts]) => setStats({ logs, traces, alerts }))
            .catch((e) => setError(e.message))
    }, [])

    return (
        <Card title="z-observer 概览">
            {error && <Alert type="error" showIcon message="读状态失败" description={error} style={{ marginBottom: 16 }} />}
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