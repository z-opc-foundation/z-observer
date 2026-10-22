import { useEffect, useState } from 'react'
import { Alert, Card, Space, Tag, Tree, Typography } from 'antd'
import { EmptyState, ErrorState, SearchInput } from '@yuku123/z-frontend-common/ui'
import { traceApi } from '../services/api.js'

/**
 * 链路页：左边最近链路，右边选中的那条画成树。
 *
 * <p>树用后端返回的父子结构直接渲染，不在前端重算 span 耗时 ——
 * 后端存的是扁平 span、乱序到达也已经组过树了，前端再拼一次只会引入第二份真相。
 */
export default function TracePage() {
    const [ids, setIds] = useState([])
    const [selected, setSelected] = useState(null)
    const [tree, setTree] = useState(null)
    const [orphanCount, setOrphanCount] = useState(0)
    const [filter, setFilter] = useState('')
    const [error, setError] = useState(null)
    const [loading, setLoading] = useState(false)

    async function loadRecent() {
        try {
            const data = await traceApi.recent(50)
            setIds(data.traceIds || [])
        } catch (e) {
            setError(e.message)
        }
    }

    async function loadTree(traceId) {
        setLoading(true)
        setError(null)
        try {
            const data = await traceApi.tree(traceId)
            setTree(data)
            setOrphanCount(data.orphanCount || 0)
            setSelected(traceId)
        } catch (e) {
            setTree(null)
            setError(e.message)
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        loadRecent()
    }, [])

    function toTreeNodes(nodes) {
        return (nodes || []).map((n) => ({
            key: n.spanId,
            title: (
                <Space size={6} wrap>
                    <Tag color={n.statusCode === 'ERROR' ? 'red' : 'blue'}>{n.name}</Tag>
                    <Typography.Text type="secondary">
                        {n.durationMillis} ms
                    </Typography.Text>
                    {n.attributes && Object.keys(n.attributes).length > 0 && (
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {Object.entries(n.attributes)
                                .map(([k, v]) => `${k}=${v}`)
                                .join(' ')}
                        </Typography.Text>
                    )}
                </Space>
            ),
            children: toTreeNodes(n.children),
        }))
    }

    const visibleIds = filter ? ids.filter((id) => id.includes(filter)) : ids

    return (
        <Space align="start" size={16} style={{ width: '100%' }}>
            <Card title="最近链路" style={{ width: 320 }}>
                {/* 端侧过滤：SearchInput 的 onChange 是 300ms 防抖，输入不再逐键重算 */}
                <SearchInput
                    placeholder="按 traceId 过滤"
                    value={filter}
                    onChange={setFilter}
                    style={{ marginBottom: 12 }}
                />
                {visibleIds.length === 0 ? (
                    <EmptyState title="还没有链路数据" description="后端还没有采集到 trace，采集恢复后这里会自动出现链路" size="sm" />
                ) : (
                    <div style={{ maxHeight: 560, overflow: 'auto' }}>
                        {visibleIds.map((id) => (
                            <div key={id} style={{ marginBottom: 6 }}>
                                <a
                                    onClick={() => loadTree(id)}
                                    style={{
                                        fontWeight: selected === id ? 'bold' : 'normal',
                                        wordBreak: 'break-all',
                                    }}
                                >
                                    {id}
                                </a>
                            </div>
                        ))}
                    </div>
                )}
            </Card>

            <Card title="链路详情" style={{ flex: 1 }} loading={loading}>
                {error && (
                    <div style={{ marginBottom: 12 }}>
                        <ErrorState error={error} title="取链路失败" onRetry={() => selected && loadTree(selected)} />
                    </div>
                )}
                {!tree && !error && <EmptyState title="未选择链路" description="从左边选一条链路" size="sm" />}
                {tree && (
                    <>
                        {orphanCount > 0 && (
                            <Alert
                                type="warning"
                                showIcon
                                style={{ marginBottom: 12 }}
                                message={`有 ${orphanCount} 个 span 的父 span 不在本条链路里（跨进程调用），已保留但单独标记`}
                            />
                        )}
                        <Tree
                            showLine
                            defaultExpandAll
                            treeData={toTreeNodes(tree.roots)}
                        />
                        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12 }}>
                            traceId {tree.traceId} · span {tree.spanCount} 个
                        </Typography.Text>
                    </>
                )}
            </Card>
        </Space>
    )
}