import { Button, Result } from 'antd'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout, ErrorBoundary } from '@yuku123/z-frontend-common'
import { menuItems, routes, appMeta } from '@yuku123/z-observer-component/pages'

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

/**
 * suit 壳：只做 layout + manifest 路由，业务页面全在 component 层。
 *
 * <p>M1 阶段不做登录壳：收端本身没有鉴权，加一个假登录只会让人以为已经受保护。
 * 真要接 z-ctc SSO 时再加，那时这一步是有意义的，不是仪式。
 */
export default function App() {
    return (
        <ErrorBoundary>
            <BrowserRouter>
                <Routes>
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
    return (
        <AppLayout
            menuItems={menuItems}
            appTitle={appMeta.title}
            appVersion="0.1.0"
            appIcon={{
                icon: <img src="/icon.png" alt={appMeta.title} style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 8 }} />,
                color: '#06b6d4',
                label: appMeta.short,
            }}
        />
    )
}