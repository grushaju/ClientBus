import { defineConfig } from '@playwright/test'
export default defineConfig({
    testDir: './e2e/stream-tab', workers: 1, timeout: 30_000,
    use: { browserName: 'chromium', headless: true },
    reporter: [['list']],
})
