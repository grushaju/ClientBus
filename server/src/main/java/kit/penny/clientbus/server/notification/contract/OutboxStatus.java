package kit.penny.clientbus.server.notification.contract;

public enum OutboxStatus {
    PENDING, IN_FLIGHT, RETRY, PUBLISHED, QUARANTINED
}
