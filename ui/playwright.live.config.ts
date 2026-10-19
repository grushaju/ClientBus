import { defineConfig } from '@playwright/test'

export default defineConfig({
    testDir: './e2e/live',
    forbidOnly: !!process.env.CI,
    workers: 1,
    retries: 0,
    outputDir: 'test-results/live',
    reporter: [['list'], ['html', { outputFolder: 'playwright-report/live', open: 'never' }]],
    use: {
        baseURL: process.env.PW_BASE_URL || 'http://localhost:8080',
        browserName: 'chromium',
        viewport: { width: 1440, height: 1000 },
        trace: 'off',
        screenshot: 'only-on-failure',
    },
})
