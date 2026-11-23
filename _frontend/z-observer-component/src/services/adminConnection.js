import { verifyAdminToken } from './api.js'
import { credentialRevision, saveVerifiedAdminToken } from './adminCredentials.js'

/** Per-view, single-flight controller. Snapshots and results never contain tokens. */
export function createAdminConnection() {
    let snapshot = Object.freeze({ phase: 'idle', error: '' })
    let active = null
    let disposed = false
    const listeners = new Set()
    const update = (phase, error = '') => {
        if (disposed) return
        snapshot = Object.freeze({ phase, error })
        listeners.forEach(listener => listener())
    }
    const cancel = () => {
        const previous = active
        active = null
        previous?.abort.abort()
        update('idle')
    }

    return {
        getSnapshot: () => snapshot,
        subscribe(listener) {
            listeners.add(listener)
            return () => listeners.delete(listener)
        },
        connect(value) {
            if (disposed) return Promise.resolve(false)
            if (active) return active.promise
            const token = typeof value === 'string' ? value.trim() : ''
            if (!token) {
                update('error', '请输入管理令牌')
                return Promise.resolve(false)
            }
            const request = { abort: new AbortController(), revision: credentialRevision() }
            active = request
            // Defer the probe so the promise is set before notifying subscribers.
            request.promise = Promise.resolve().then(async () => {
                try {
                    if (disposed || active !== request) return false
                    await verifyAdminToken(token, request.abort.signal)
                    if (disposed || active !== request) return false
                    if (request.revision !== credentialRevision()) {
                        update('idle')
                        return false
                    }
                    saveVerifiedAdminToken(token)
                    update('connected')
                    return true
                } catch (error) {
                    if (disposed || active !== request) return false
                    if (request.revision !== credentialRevision() || error.code === 'ERR_CANCELED') {
                        update('idle')
                    } else {
                        // Only sanitized errors originate from the probe/storage modules.
                        update('error', error.message)
                    }
                    return false
                } finally {
                    if (active === request) active = null
                }
            })
            update('connecting')
            return request.promise
        },
        cancel,
        dispose() {
            disposed = true
            cancel()
            listeners.clear()
        },
    }
}
