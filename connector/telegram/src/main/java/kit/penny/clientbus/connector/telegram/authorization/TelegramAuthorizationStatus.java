package kit.penny.clientbus.connector.telegram.authorization;

public enum TelegramAuthorizationStatus {
    WAIT_PHONE_NUMBER,
    WAIT_CODE,
    WAIT_PASSWORD,
    WAIT_EMAIL,
    READY,
    ERROR,
    CLOSED
}