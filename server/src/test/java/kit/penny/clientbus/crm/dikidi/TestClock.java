package kit.penny.clientbus.crm.dikidi;

import java.time.*;

/**
 * 13.4: фикстура времени для CRM-кэша. Одноимённая копия уехала в тестовое дерево
 * integration/dikidi вместе с DikidiRequestsTest; тестовые классы между модулями
 * offline-сборкой не экспортируются, поэтому здесь держим свой экземпляр.
 */
final class TestClock extends Clock {
    Instant now = Instant.parse("2026-11-10T10:00:00Z");
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now; }
}
