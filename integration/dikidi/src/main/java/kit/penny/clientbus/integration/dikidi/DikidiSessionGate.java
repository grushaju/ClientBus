package kit.penny.clientbus.integration.dikidi;

import java.util.UUID;

/**
 * Дешёвая проверка единственного активного runtime технического подключения
 * (13.4): сборка реализует порт lease/heartbeat-строкой в своей БД. Второй
 * экземпляр с тем же connectionId приводит к явной деградации (исключение) и
 * алерту у реализации, а не к молчаливой двойной работе с общей сессией.
 * Полноценный claim/lease многоэкземплярной топологии — 13.8.
 */
public interface DikidiSessionGate {
    DikidiSessionGate NOOP = connectionId -> { };
    /** Бросает DikidiFailure, когда этому runtime нельзя работать с соединением. */
    void requireUsable(UUID connectionId);

    default void requireUsable(UUID connectionId, long credentialVersion) {
        requireUsable(connectionId);
    }
}
