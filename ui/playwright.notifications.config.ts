import { readFileSync } from 'node:fs'
import { defineConfig, devices } from '@playwright/test'
const path = process.env.PW_NOTIFICATION_MANIFEST
if (!path) throw new Error('Run NotificationBrowserAcceptance to start the isolated server.')
const manifest = JSON.parse(readFileSync(path, 'utf8'))
export default defineConfig({
    testDir: './e2e/notifications', workers: 1, fullyParallel: false, retries: 0, timeout: 90_000,
    expect: { timeout: 12_000 },
    outputDir: '../server/target/notification-browser-results',
    reporter: [['list']],
    use: { baseURL: manifest.baseURL, serviceWorkers: 'block', trace: 'off', screenshot: 'only-on-failure' },
    projects: [
        { name: 'desktop', use: { browserName: 'chromium', viewport: { width: 1440, height: 1000 } } },
        { name: 'mobile', use: { ...devices['Pixel 7'], browserName: 'chromium' } },
    ],
})
