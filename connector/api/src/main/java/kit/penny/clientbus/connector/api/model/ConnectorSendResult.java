package kit.penny.clientbus.connector.api.model;

public record ConnectorSendResult(

        /**
         * ID сообщения на внешней платформе.
         */
        String externalId,
        java.util.UUID sendAttemptId

) {
    public ConnectorSendResult(String externalId) { this(externalId, null); }
}
