export const ADMIN_TOKEN_KEY = 'z-observer-admin-token'

const listeners = new Set()
let revision = 0

function storage() {
    return typeof window === 'undefined' ? undefined : window.sessionStorage
}

// Internal only: public consumers receive connection state, never the credential.
export function readAdminToken() {
    try {
        return storage()?.getItem(ADMIN_TOKEN_KEY)?.trim() || ''
    } catch {
        return ''
    }
}

export const hasAdminToken = () => Boolean(readAdminToken())
export const credentialRevision = () => revision
export function subscribeAdminToken(listener) {
    listeners.add(listener)
    return () => listeners.delete(listener)
}

function changed() {
    revision += 1
    listeners.forEach(listener => listener())
}

// Only the connection controller writes, after a successful read-only probe.
export function saveVerifiedAdminToken(token) {
    try {
        const session = storage()
        if (!session) throw new Error()
        session.setItem(ADMIN_TOKEN_KEY, token)
    } catch {
        throw new Error('无法保存会话凭据，请允许此站点使用会话存储后重试')
    }
    changed()
}

export function clearAdminToken() {
    try {
        storage()?.removeItem(ADMIN_TOKEN_KEY)
    } catch {
        throw new Error('无法清除会话凭据，请检查浏览器会话存储设置')
    }
    // Invalidates in-flight verification even when no token was saved yet.
    changed()
}
