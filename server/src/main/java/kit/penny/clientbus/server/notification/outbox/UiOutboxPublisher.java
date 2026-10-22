package kit.penny.clientbus.server.notification.outbox;

public interface UiOutboxPublisher {
    void publish(String key, String payload) throws Exception;
}
