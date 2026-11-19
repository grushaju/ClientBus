package kit.penny.clientbus.connector.api.model;

public record ConnectorSendResult(

        /**
         * ID сообщения на внешней платформе.
         */
        String externalId,
        java.util.UUID sendAttemptId,
        boolean completed

) {
    public ConnectorSendResult(String externalId) { this(externalId, null, false); }
    public ConnectorSendResult(String externalId, java.util.UUID sendAttemptId) {
        this(externalId, sendAttemptId, false);
    }
}
