import { readFileSync } from 'node:fs'
import { defineConfig } from '@playwright/test'
const path = process.env.PW_NOTIFICATION_LOAD_MANIFEST
if (!path) throw new Error('Run NotificationLoadBrowserAcceptance to start the isolated profile.')
const manifest = JSON.parse(readFileSync(path, 'utf8'))
export default defineConfig({
    testDir: './e2e/notification-acceptance', testMatch: '**/load.spec.ts',
    workers: 1, fullyParallel: false, retries: 0, timeout: 720_000,
    expect: { timeout: 15_000 }, reporter: [['list']],
    outputDir: '../server/target/notification-load-browser-results',
    use: { baseURL: manifest.baseURLs[0], browserName: 'chromium', viewport: { width: 1440, height: 1000 },
        serviceWorkers: 'block', trace: 'off', screenshot: 'only-on-failure', actionTimeout: 15_000 },
})
