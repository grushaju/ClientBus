package kit.penny.clientbus.integration.dikidi;

import java.time.Instant;
import java.util.UUID;

/**
 * Техническая пауза соединения (13.4). Порт реализует сборка (server): пауза
 * переживает перезапуск и общая между процессами. Сюда не попадают ни
 * учётные данные, ни CRM-типы.
 */
public interface DikidiTrafficPort {
    record Pause(Instant notBefore, int failures) {
        public static final Pause NONE = new Pause(null, 0);
    }
    Pause current(UUID connectionId);
    Pause block(UUID connectionId, Instant notBefore);
}
