import { readFileSync } from 'node:fs'
import { defineConfig, devices } from '@playwright/test'

const path = process.env.PW_NOTIFICATION_ACCEPTANCE_MANIFEST
if (!path) throw new Error('Run NotificationStageBrowserAcceptance to start both isolated backend instances.')
const manifest = JSON.parse(readFileSync(path, 'utf8'))
export default defineConfig({
    testDir: './e2e/notification-acceptance', testIgnore: ['**/load.spec.ts'],
    workers: 1, fullyParallel: false, retries: 0, timeout: 150_000,
    expect: { timeout: 15_000 }, reporter: [['list']],
    outputDir: '../server/target/notification-stage-browser-results',
    use: { baseURL: manifest.baseURLs[0], serviceWorkers: 'block', trace: 'off', screenshot: 'only-on-failure' },
    projects: [
        { name: 'desktop', use: { browserName: 'chromium', viewport: { width: 1440, height: 1000 } } },
        { name: 'mobile', use: { ...devices['Pixel 7'], browserName: 'chromium' } },
    ],
})
