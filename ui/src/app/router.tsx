import { lazy, type ReactNode } from 'react'
import {
    createBrowserRouter,
} from 'react-router-dom'

import App from './App'
import PageBoundary from './PageBoundary'

import ProtectedLayout
    from '../components/layout/ProtectedLayout'

import RoleProtectedLayout
    from '../components/layout/RoleProtectedLayout'

const LoginPage = lazy(() => import('../pages/LoginPage'))
const InboxPage = lazy(() => import('../pages/InboxPage'))
const ClientsPage = lazy(() => import('../pages/ClientsPage'))
const ClientDetailsPage = lazy(() => import('../pages/ClientDetailsPage'))
const ChannelsPage = lazy(() => import('../pages/ChannelsPage'))
const EmployeesPage = lazy(() => import('../pages/EmployeesPage'))
const WorkspacesPage = lazy(() => import('../pages/WorkspacesPage'))
const WorkspaceDetailsPage = lazy(() => import('../pages/WorkspaceDetailsPage'))
const SettingsPage = lazy(() => import('../pages/SettingsPage'))
const CrmPage = lazy(() => import('../pages/CrmPage'))

function loadPage(section: string, element: ReactNode) {
    // Reset a failed/suspended page when changing sections, but preserve page
    // state on query changes (CRM tabs/filters) and within the same section.
    return <PageBoundary key={section}>{element}</PageBoundary>
}

export const router =
    createBrowserRouter([
        {
            path: '/',
            element: <App />,
            children: [
                {
                    path: 'login',
                    element: loadPage('login', <LoginPage />),
                },
                {
                    element:
                        <ProtectedLayout />,
                    children: [
                        {
                            index: true,
                            element:
                                loadPage('inbox', <InboxPage />),
                        },
                        {
                            path: 'inbox',
                            children: [
                                {
                                    index: true,
                                    element:
                                        loadPage('inbox', <InboxPage />),
                                },
                                {
                                    path:
                                        ':conversationId',
                                    element:
                                        loadPage('inbox', <InboxPage />),
                                },
                            ],
                        },
                        {
                            path: 'clients',
                            children: [
                                {
                                    index: true,
                                    element:
                                        loadPage('clients', <ClientsPage />),
                                },
                                {
                                    path:
                                        ':clientId',
                                    element:
                                        loadPage('client-details', <ClientDetailsPage />),
                                },
                            ],
                        },
                        {
                            path: 'channels/*',
                            element:
                                loadPage('channels', <ChannelsPage />),
                        },
                        {
                            path: 'settings/*',
                            element:
                                loadPage('settings', <SettingsPage />),
                        },
                        { path: 'crm', element: loadPage('crm', <CrmPage />) },
                        {
                            element: (
                                <RoleProtectedLayout
                                    allowedRoles={[
                                        'SUPER_ADMIN',
                                    ]}
                                />
                            ),
                            children: [
                                {
                                    path:
                                        'employees/*',
                                    element:
                                        loadPage('employees', <EmployeesPage />),
                                },
                                {
                                    path:
                                        'workspaces',
                                    children: [
                                        {
                                            index: true,
                                            element:
                                                loadPage('workspaces', <WorkspacesPage />),
                                        },
                                        {
                                            path:
                                                ':workspaceId',
                                            element:
                                                loadPage('workspace-details', <WorkspaceDetailsPage />),
                                        },
                                    ],
                                },
                            ],
                        },
                    ],
                },
            ],
        },
    ])
