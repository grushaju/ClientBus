package kit.penny.clientbus.connector.api.host;

import kit.penny.clientbus.connector.api.model.ConnectorInboundMessage;

/**
 * Host port for publishing one normalized inbound platform message. The host owns transactional
 * persistence, deduplication and UI events; the connector never sees repositories or Kafka.
 */
public interface HostInboundPublisher {

    void publish(
            ConnectorInboundMessage message
    );
}
