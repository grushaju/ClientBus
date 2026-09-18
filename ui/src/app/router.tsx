import {
    createBrowserRouter
} from 'react-router-dom'

import App from './App'

import LoginPage
    from '../pages/LoginPage'

import ProtectedLayout
    from '../components/layout/ProtectedLayout'

import RoleProtectedLayout
    from '../components/layout/RoleProtectedLayout'

import InboxPage
    from '../pages/InboxPage'

import ClientsPage
    from '../pages/ClientsPage'

import ChannelsPage
    from '../pages/ChannelsPage'

import EmployeesPage
    from '../pages/EmployeesPage'

import WorkspacesPage
    from '../pages/WorkspacesPage'

import SettingsPage
    from '../pages/SettingsPage'

export const router =
    createBrowserRouter([
        {
            path: '/',
            element: <App />,
            children: [
                {
                    path: 'login',
                    element: <LoginPage />
                },
                {
                    element:
                        <ProtectedLayout />,
                    children: [
                        {
                            index: true,
                            element:
                                <InboxPage />
                        },
                        {
                            path: 'inbox',
                            children: [
                                {
                                    index: true,
                                    element:
                                        <InboxPage />
                                },
                                {
                                    path:
                                        ':conversationId',
                                    element:
                                        <InboxPage />
                                }
                            ]
                        },
                        {
                            path: 'clients/*',
                            element:
                                <ClientsPage />
                        },
                        {
                            path: 'channels/*',
                            element:
                                <ChannelsPage />
                        },
                        {
                            path: 'settings/*',
                            element:
                                <SettingsPage />
                        },
                        {
                            element:
                                (
                                    <RoleProtectedLayout
                                        allowedRoles={[
                                            'SUPER_ADMIN'
                                        ]}
                                    />
                                ),
                            children: [
                                {
                                    path:
                                        'employees/*',
                                    element:
                                        <EmployeesPage />
                                },
                                {
                                    path:
                                        'workspaces/*',
                                    element:
                                        <WorkspacesPage />
                                }
                            ]
                        }
                    ]
                }
            ]
        }
    ])