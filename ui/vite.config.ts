import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { recoveryPolicy } from './src/api/events/recoveryPolicy'

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), 'VITE_UI_RECOVERY_')
    const policy: Record<string, number | boolean> = {}
    const names = { ENABLED: 'enabled', FALLBACK_MS: 'fallbackMs', FALLBACK_MAX_MS: 'fallbackMaxMs', CONTROL_MS: 'controlMs',
        JITTER: 'jitter', MAX_CONCURRENT: 'maxConcurrent', MAX_QUEUED: 'maxQueued', REQUEST_SPACING_MS: 'requestSpacingMs',
        RETRY_MS: 'retryMs', RETRY_MAX_MS: 'retryMaxMs' }
    for (const [suffix, name] of Object.entries(names)) {
        const value = env['VITE_UI_RECOVERY_' + suffix]
        if (value !== undefined) {
            if (name === 'enabled' && value !== 'true' && value !== 'false') throw new Error('Invalid UI recovery flag')
            policy[name] = name === 'enabled' ? value === 'true' : Number(value)
            if (typeof policy[name] === 'number' && !Number.isFinite(policy[name])) throw new Error('Invalid UI recovery value: ' + name)
        }
    }
    return {
    define: { __UI_RECOVERY_POLICY__: JSON.stringify(recoveryPolicy(policy)) },
    plugins: [react()],
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true
            }
        }
    }
    }
})
