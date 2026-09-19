import { NavLink } from 'react-router-dom'

import { useAuth } from '../../auth/AuthContext'

function Sidebar() {
    const {
        isSuperAdmin,
        logout
    } = useAuth()

    return (
        <aside className="sidebar">
            <nav className="sidebar-nav">
                <NavLink to="/inbox">
                    Диалоги
                </NavLink>

                <NavLink to="/clients">
                    Клиенты
                </NavLink>

                <NavLink to="/channels">
                    Каналы
                </NavLink>

                {isSuperAdmin && (
                    <>
                        <NavLink to="/employees">
                            Сотрудники
                        </NavLink>

                        <NavLink to="/workspaces">
                            Пространства
                        </NavLink>
                    </>
                )}

                <NavLink to="/settings">
                    Настройки
                </NavLink>
            </nav>

            <div className="sidebar-footer">
                <button
                    type="button"
                    onClick={logout}
                >
                    Выйти
                </button>
            </div>
        </aside>
    )
}

export default Sidebar