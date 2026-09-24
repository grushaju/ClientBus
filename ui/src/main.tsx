import './index.css'

import './pages/InboxPage.css'
import './pages/ChannelsPage.css'
import './pages/ClientsPage.css'
import './pages/ClientDetailsPage.css'
import './pages/EmployeesPage.css'
import './pages/SettingsPage.css'
import './pages/WorkspaceDetailsPage.css'
import './pages/WorkspacesPage.css'
import './pages/LoginPage.css'

import './components/layout/AppLayout.css'
import './components/layout/Sidebar.css'

import './components/employees/Employees.css'
import './components/channels/Channels.css'
import './components/clients/Clients.css'
import './components/workspaces/Workspaces.css'

import React from 'react'
import ReactDOM from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'

import { router } from './app/router'
import { AuthProvider } from './auth/AuthContext'

import {WorkspaceProvider} from "./workspace/WorkspaceContext";

ReactDOM.createRoot(
    document.getElementById('root')!
).render(
    <React.StrictMode>
        <AuthProvider>
            <WorkspaceProvider>
                <RouterProvider router={router} />
            </WorkspaceProvider>
        </AuthProvider>
    </React.StrictMode>
)