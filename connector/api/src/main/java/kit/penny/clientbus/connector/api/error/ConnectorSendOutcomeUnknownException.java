package kit.penny.clientbus.connector.api.error;

/**
 * The connector cannot prove whether the external platform accepted the message: the host
 * must NOT release the delivery claim, must NOT mark the delivery failed and must NOT let
 * Kafka retry the send (13.2b send-outcome protocol). The attempt is held until the real
 * external outcome arrives (platform callback) or an operator resolves it.
 */
public class ConnectorSendOutcomeUnknownException extends RuntimeException {

    public ConnectorSendOutcomeUnknownException(String message) {
        super(message);
    }

    public ConnectorSendOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
