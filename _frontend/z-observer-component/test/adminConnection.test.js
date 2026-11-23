import { beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import axios, { AxiosError } from 'axios'

// Exercise the real axios instance/interceptors, but never open a socket.
let requests, adapter
axios.defaults.headers.common.Authorization = 'Bearer unrelated-host-default'
axios.defaults.auth = { username: 'host', password: 'unrelated-host-password' }
axios.defaults.adapter = async config => {
    requests.push(config)
    return adapter(config)
}
const api = await import('../src/services/api.js')
const { createAdminConnection } = await import('../src/services/adminConnection.js')
const { ADMIN_TOKEN_KEY, hasAdminToken, clearAdminToken, subscribeAdminToken } = await import('../src/services/adminCredentials.js')
const storage = () => {
    const values = new Map()
    return {
        getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, String(value)),
        removeItem: key => values.delete(key),
    }
}
const success = config => ({ status: 200, statusText: 'OK', headers: {}, config, data: { liveLogs: 0, evictedLogs: 0 } })
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => {
    let resolve
    const promise = new Promise(done => { resolve = done })
    return { promise, resolve }
}
beforeEach(() => {
    globalThis.window = { sessionStorage: storage(), localStorage: storage() }
    requests = []
    adapter = success
})

test('required and whitespace-only input never requests or persists', async () => {
    const connection = createAdminConnection()
    for (const input of ['', '  \n\t ', null]) assert.equal(await connection.connect(input), false)
    assert.equal(requests.length, 0)
    assert.equal(hasAdminToken(), false)
    assert.equal(connection.getSnapshot().error, '请输入管理令牌')
    connection.dispose()
})

test('read-only candidate verification trims and saves only after success', async () => {
    const gate = deferred()
    adapter = async config => {
        assert.equal(config.url, '/api/logs/status')
        assert.equal(config.method, 'get')
        assert.equal(config.headers.get('Authorization'), 'Bearer fixture-candidate')
        assert.equal(hasAdminToken(), false)
        await gate.promise
        return success(config)
    }
    const connection = createAdminConnection()
    const result = connection.connect('  fixture-candidate \n')
    await tick()
    assert.equal(connection.getSnapshot().phase, 'connecting')
    assert.equal(hasAdminToken(), false)
    gate.resolve()
    assert.equal(await result, true)
    assert.equal(window.sessionStorage.getItem(ADMIN_TOKEN_KEY), 'fixture-candidate')
    assert.equal(JSON.stringify(connection.getSnapshot()).includes('fixture-candidate'), false)
    connection.dispose()
})

test('all existing API methods read the latest session credential through axios', async () => {
    await api.logApi.status()
    assert.equal(requests.at(-1).headers.has('Authorization'), false)
    assert.equal(requests.at(-1).auth, null)
    assert.equal(axios.defaults.headers.common.Authorization, 'Bearer unrelated-host-default')
    window.sessionStorage.setItem(ADMIN_TOKEN_KEY, 'fixture-restored')
    const calls = [
        () => api.logApi.search({ q: 'test' }), () => api.logApi.status(),
        () => api.traceApi.recent(), () => api.traceApi.tree('trace/id'),
        () => api.alertApi.list(), () => api.alertApi.addSilence({}), () => api.alertApi.addInhibit({}),
        () => api.metricApi.instant('up'), () => api.metricApi.range('up', 1, 2, 1),
    ]
    for (const call of calls) {
        await call()
        assert.equal(requests.at(-1).headers.get('Authorization'), 'Bearer fixture-restored')
    }
    assert.equal(hasAdminToken(), true)
    clearAdminToken()
    await api.logApi.status()
    assert.equal(requests.at(-1).headers.has('Authorization'), false)
})

for (const status of [401, 500, undefined]) {
    test(`failure ${status ?? 'network'} stays retryable, preserves old token and sanitizes errors`, async () => {
        window.sessionStorage.setItem(ADMIN_TOKEN_KEY, 'fixture-old')
        adapter = config => {
            throw new AxiosError('fixture-replacement', 'ERR_BAD_RESPONSE', config, {}, status ? { status, data: { error: 'fixture-replacement' } } : undefined)
        }
        const connection = createAdminConnection()
        assert.equal(await connection.connect('fixture-replacement'), false)
        assert.equal(requests.at(-1).headers.get('Authorization'), 'Bearer fixture-replacement')
        assert.equal(connection.getSnapshot().phase, 'error')
        assert.equal(JSON.stringify(connection.getSnapshot()).includes('fixture-replacement'), false)
        assert.equal(window.sessionStorage.getItem(ADMIN_TOKEN_KEY), 'fixture-old')
        await assert.rejects(api.logApi.status(), error => {
            assert.equal(error.config, undefined)
            assert.equal(error.response, undefined)
            assert.equal(error.message.includes('fixture-replacement'), false)
            return true
        })
        adapter = success
        assert.equal(await connection.connect('fixture-replacement'), true)
        assert.equal(window.sessionStorage.getItem(ADMIN_TOKEN_KEY), 'fixture-replacement')
        connection.dispose()
    })
}

test('single-flight repeated submissions share one request and one safe result', async () => {
    const gate = deferred()
    adapter = async config => { await gate.promise; return success(config) }
    const connection = createAdminConnection()
    const first = connection.connect('fixture-first')
    const second = connection.connect('fixture-ignored')
    assert.equal(first, second)
    await tick()
    assert.equal(requests.length, 1)
    gate.resolve()
    assert.equal(await first, true)
    assert.equal(window.sessionStorage.getItem(ADMIN_TOKEN_KEY), 'fixture-first')
    connection.dispose()
})

test('dispose/unmount aborts and ignores late completion without notifying or saving', async () => {
    const gate = deferred()
    adapter = async config => { await gate.promise; return success(config) }
    const connection = createAdminConnection()
    let notifications = 0
    connection.subscribe(() => { notifications += 1 })
    const result = connection.connect('fixture-late')
    await tick()
    connection.dispose()
    assert.equal(requests[0].signal.aborted, true)
    gate.resolve()
    assert.equal(await result, false)
    assert.equal(notifications, 1)
    assert.equal(hasAdminToken(), false)
    assert.equal(await connection.connect('fixture-after-dispose'), false)
})

test('cancel then retry cannot be overwritten by a stale completion', async () => {
    const gate = deferred()
    adapter = async config => { await gate.promise; return success(config) }
    const connection = createAdminConnection()
    const first = connection.connect('fixture-stale')
    await tick()
    connection.cancel()
    adapter = success
    assert.equal(await connection.connect('fixture-new'), true)
    gate.resolve()
    assert.equal(await first, false)
    assert.equal(window.sessionStorage.getItem(ADMIN_TOKEN_KEY), 'fixture-new')
    connection.dispose()
})

test('disconnect invalidates pending probes and leaves every unrelated key untouched', async () => {
    for (const area of [window.localStorage, window.sessionStorage]) {
        for (const key of ['token', 'userInfo', 'other-app-token']) area.setItem(key, `sentinel-${key}`)
    }
    window.localStorage.setItem(ADMIN_TOKEN_KEY, 'unrelated-local-value')
    const gate = deferred()
    adapter = async config => { await gate.promise; return success(config) }
    const connection = createAdminConnection()
    const pending = connection.connect('fixture-pending')
    await tick()
    let notifications = 0
    const unsubscribe = subscribeAdminToken(() => { notifications += 1 })
    clearAdminToken()
    gate.resolve()
    assert.equal(await pending, false)
    assert.equal(hasAdminToken(), false)
    assert.equal(notifications, 1)
    for (const area of [window.localStorage, window.sessionStorage]) {
        for (const key of ['token', 'userInfo', 'other-app-token']) assert.equal(area.getItem(key), `sentinel-${key}`)
    }
    assert.equal(window.localStorage.getItem(ADMIN_TOKEN_KEY), 'unrelated-local-value')
    unsubscribe()
    connection.dispose()
})

test('a second successful controller supersedes a pending first controller', async () => {
    const gate = deferred()
    adapter = async config => { await gate.promise; return success(config) }
    const first = createAdminConnection(), second = createAdminConnection()
    const stale = first.connect('fixture-stale')
    await tick()
    adapter = success
    assert.equal(await second.connect('fixture-current'), true)
    gate.resolve()
    assert.equal(await stale, false)
    assert.equal(window.sessionStorage.getItem(ADMIN_TOKEN_KEY), 'fixture-current')
    first.dispose(); second.dispose()
})

test('unexpected successful response is not accepted as a verified connection', async () => {
    adapter = config => ({ ...success(config), data: '<html>not an API response</html>' })
    const connection = createAdminConnection()
    assert.equal(await connection.connect('fixture-invalid-response'), false)
    assert.equal(hasAdminToken(), false)
    assert.equal(connection.getSnapshot().phase, 'error')
    connection.dispose()
})

test('storage write denial is safe, explicit and retryable', async () => {
    const connection = createAdminConnection()
    const original = window.sessionStorage.setItem
    window.sessionStorage.setItem = () => { throw new Error('fixture-secret') }
    assert.equal(await connection.connect('fixture-secret'), false)
    assert.equal(hasAdminToken(), false)
    assert.match(connection.getSnapshot().error, /无法保存会话凭据/)
    window.sessionStorage.setItem = original
    assert.equal(await connection.connect('fixture-secret'), true)
    window.sessionStorage.removeItem = () => { throw new Error('fixture-secret') }
    assert.throws(clearAdminToken, /无法清除会话凭据/)
    assert.equal(hasAdminToken(), true)
    connection.dispose()
})

test('absent or inaccessible browser storage never falls back to shared localStorage', async () => {
    delete globalThis.window
    assert.equal(hasAdminToken(), false)
    const connection = createAdminConnection()
    assert.equal(await connection.connect('fixture-ssr'), false)
    globalThis.window = { get sessionStorage() { throw new Error('blocked') }, localStorage: storage() }
    assert.equal(hasAdminToken(), false)
    await api.logApi.status()
    assert.equal(requests.at(-1).headers.has('Authorization'), false)
    assert.equal(window.localStorage.getItem(ADMIN_TOKEN_KEY), null)
    connection.dispose()
})
