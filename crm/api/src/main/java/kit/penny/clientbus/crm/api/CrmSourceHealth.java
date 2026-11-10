package kit.penny.clientbus.crm.api;

import java.util.UUID;

/** Durable connection health order reserved by the owner before external I/O, never by a later waiter. */
public record CrmSourceHealth(UUID connectionId,long credentialVersion,long generation) {}
