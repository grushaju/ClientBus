package kit.penny.clientbus.connector.api.error;

/**
 * The connector proved that the external platform did not accept the message and no other
 * external effect happened: the host may release the outbound delivery claim and let the
 * platform retry the send (13.2b send-outcome protocol).
 */
public class ConnectorSendRejectedException extends RuntimeException {

    public ConnectorSendRejectedException(String message) {
        super(message);
    }

    public ConnectorSendRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
