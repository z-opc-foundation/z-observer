import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Alert, Button, Card, Input, Space, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { clearAdminToken, createAdminConnection, hasAdminToken, subscribeAdminToken } from '@yuku123/z-observer-component'

export default function AdminConnection() {
    const navigate = useNavigate()
    const connected = useSyncExternalStore(subscribeAdminToken, hasAdminToken, () => false)
    const controller = useRef(null)
    const [state, setState] = useState({ phase: 'idle', error: '' })
    const [token, setToken] = useState('')
    const [editing, setEditing] = useState(false)
    const [storageError, setStorageError] = useState('')
    const pending = state.phase === 'connecting'

    useEffect(() => {
        const current = createAdminConnection()
        controller.current = current
        const unsubscribe = current.subscribe(() => setState(current.getSnapshot()))
        return () => {
            controller.current = null
            unsubscribe()
            current.dispose()
        }
    }, [])

    const submit = async event => {
        event.preventDefault()
        const current = controller.current
        if (!current) return
        setStorageError('')
        const result = current.connect(token)
        setToken('')
        if (await result && controller.current === current) navigate('/z-observer/home', { replace: true })
    }
    const disconnect = () => {
        controller.current?.cancel()
        setToken('')
        try {
            clearAdminToken()
            setEditing(false)
            setStorageError('')
        } catch (error) {
            setStorageError(error.message)
        }
    }

    return (
        <main style={{ width: '100%', maxWidth: 520, margin: '24px auto', padding: '0 12px', boxSizing: 'border-box' }}>
            <Button type="link" onClick={() => navigate('/z-observer/docs')} style={{ paddingLeft: 0, marginBottom: 8 }}>查看组件文档</Button>
            <Card styles={{ body: { padding: 20 } }}>
                <Typography.Title level={3} style={{ marginTop: 0 }}>管理令牌连接</Typography.Title>
                <Typography.Paragraph type="secondary">
                    使用管理员提供的令牌连接 z-observer。仅通过只读日志状态接口验证，不创建帐号或会话。
                    验证成功后凭据只保存在当前标签页的会话存储中，刷新可继续使用；服务端负责鉴权。
                </Typography.Paragraph>
                {connected && (
                    <Space orientation="vertical" style={{ width: '100%', marginBottom: 16 }}>
                        <Alert type="success" title="已连接（已保存本标签页凭据）" />
                        <Space wrap>
                            <Button onClick={() => { setEditing(true); setToken('') }} disabled={pending}>更换令牌</Button>
                            <Button danger onClick={disconnect}>断开连接</Button>
                            <Button onClick={() => navigate('/z-observer/home')}>返回看板</Button>
                        </Space>
                    </Space>
                )}
                {(!connected || editing) && (
                    <form onSubmit={submit} aria-label="管理令牌连接表单">
                        <label htmlFor="observer-admin-token" style={{ display: 'block', marginBottom: 8 }}>管理令牌（必填）</label>
                        <Input.Password
                            id="observer-admin-token"
                            name="observer-admin-token"
                            autoComplete="off"
                            required
                            visibilityToggle={false}
                            value={token}
                            onChange={event => setToken(event.target.value)}
                            disabled={pending}
                            aria-describedby="observer-token-help"
                        />
                        <Typography.Paragraph id="observer-token-help" type="secondary" style={{ marginTop: 8 }}>
                            不展示已保存的令牌。更换失败不会覆盖原凭据。
                        </Typography.Paragraph>
                        <Space wrap>
                            <Button type="primary" htmlType="submit" loading={pending} disabled={pending}>验证并连接</Button>
                            {pending && <Button onClick={() => { controller.current?.cancel(); setToken('') }}>取消验证</Button>}
                        </Space>
                    </form>
                )}
                {(state.error || storageError) && <Alert role="alert" type="error" showIcon title={storageError || state.error} style={{ marginTop: 16 }} />}
            </Card>
        </main>
    )
}
