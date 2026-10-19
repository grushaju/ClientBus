import { readFileSync } from 'node:fs'
import { defineConfig, devices } from '@playwright/test'

const manifestPath = process.env.PW_ACCEPTANCE_MANIFEST
if (!manifestPath) throw new Error('Run the isolated server harness: see e2e/README.md (InboxBrowserAcceptance).')
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))

export default defineConfig({
    testDir: './e2e/acceptance',
    fullyParallel: false,
    workers: 1,
    retries: 0,
    maxFailures: 2,
    timeout: 45_000,
    expect: { timeout: 10_000 },
    outputDir: '../server/target/inbox-browser-results',
    reporter: [['list'], ['html', { outputFolder: '../server/target/inbox-browser-report', open: 'never' }]],
    use: {
        baseURL: manifest.baseURL,
        serviceWorkers: 'block',
        trace: 'off',
        screenshot: 'only-on-failure',
    },
    projects: [
        { name: 'desktop', use: { browserName: 'chromium', viewport: { width: 1440, height: 1000 } } },
        { name: 'mobile', use: { ...devices['Pixel 7'], browserName: 'chromium' } },
    ],
})
