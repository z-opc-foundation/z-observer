import { useSyncExternalStore } from 'react'
import { Button, Result, message } from 'antd'
import { BrowserRouter, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { AppLayout, ErrorBoundary } from '@yuku123/z-frontend-common'
import { menuItems, routes, appMeta } from '@yuku123/z-observer-component/pages'
import { clearAdminToken, hasAdminToken, subscribeAdminToken } from '@yuku123/z-observer-component'
import AdminConnection from './AdminConnection.jsx'

function NotFound() {
    return (
        <Result
            status="404"
            title="页面不存在"
            subTitle="路由表里没有这条路径。"
            extra={
                <Button type="primary" onClick={() => { window.location.href = '/z-observer/home' }}>
                    去首页
                </Button>
            }
        />
    )
}

/** suit 提供管理令牌连接入口；服务端 Bearer 鉴权才是安全边界。 */
export default function App() {
    return (
        <ErrorBoundary>
            <BrowserRouter>
                <Routes>
                    <Route path="/z-observer/login" element={<AdminConnection />} />
                    <Route element={<Shell />}>
                        <Route path="/" element={<Navigate to={menuItems[0].key} replace />} />
                        {routes.map((r) => (
                            <Route key={r.path} path={r.path} element={<r.Component />} />
                        ))}
                        <Route path="*" element={<NotFound />} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </ErrorBoundary>
    )
}

function Shell() {
    const navigate = useNavigate()
    const connected = useSyncExternalStore(subscribeAdminToken, hasAdminToken, () => false)
    const [feedback, contextHolder] = message.useMessage()
    const disconnect = () => {
        try {
            clearAdminToken()
            navigate('/z-observer/login')
        } catch (error) {
            feedback.error(error.message)
        }
    }
    return (
        <AppLayout
            loginPath="/z-observer/login"
            headerExtra={<>{contextHolder}{connected && <Button size="small" danger onClick={disconnect}>断开连接</Button>}</>}
            menuItems={menuItems}
            appTitle={appMeta.title}
            appShort="observer"
            appVersion="0.1.0"
            appIcon={{
                icon: <img src="/icon.png" alt={appMeta.title} style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 8 }} />,
                color: '#06b6d4',
                label: appMeta.short,
            }}
        />
    )
}