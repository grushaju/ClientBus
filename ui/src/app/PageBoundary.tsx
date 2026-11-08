import { Component, Suspense, type ReactNode } from 'react'
import './PageBoundary.css'

/** Kept below the application shell so navigation and notifications stay mounted. */
export default class PageBoundary extends Component<
    { children: ReactNode },
    { failed: boolean }
> {
    state = { failed: false }

    static getDerivedStateFromError() {
        return { failed: true }
    }

    render() {
        if (this.state.failed) {
            return (
                <div className="page-status" role="alert">
                    <h2>Не удалось открыть страницу</h2>
                    <p>Обновите страницу или перейдите в другой раздел через меню.</p>
                    <button type="button" className="ui-button ui-button-primary"
                        onClick={() => window.location.reload()}>
                        Обновить страницу
                    </button>
                </div>
            )
        }

        return (
            <Suspense fallback={
                <div className="page-status" role="status" aria-live="polite">
                    Загрузка страницы…
                </div>
            }>
                {this.props.children}
            </Suspense>
        )
    }
}
