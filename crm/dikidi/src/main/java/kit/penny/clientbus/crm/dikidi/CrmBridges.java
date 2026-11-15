package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.CrmAccess;
import kit.penny.clientbus.crm.api.CrmFailure;
import kit.penny.clientbus.crm.api.CrmTrafficPort;
import kit.penny.clientbus.integration.dikidi.DikidiClient;
import kit.penny.clientbus.integration.dikidi.DikidiCredential;
import kit.penny.clientbus.integration.dikidi.DikidiFailure;
import kit.penny.clientbus.integration.dikidi.DikidiTrafficPort;

import java.time.Instant;
import java.util.UUID;

/**
 * Единственный мост технического контракта (integration/dikidi) в CRM-контракт
 * (crm/api), 13.4. Коды технической ошибки отображаются 1:1 по именам;
 * retryAt/notDispatched сохраняются. Больше нигде DikidiFailure наружу не
 * выходит (server видит только CrmFailure).
 */
public final class CrmBridges {
    private CrmBridges() {}
    static DikidiClient.Settings settings(DikidiProvider.Settings value) {
        return new DikidiClient.Settings(value.connectTimeout(), value.requestTimeout(), value.maxResponseBytes());
    }
    public static CrmFailure failure(DikidiFailure error) {
        return new CrmFailure(CrmFailure.Code.valueOf(error.code().name()), error.retryAt(), error.notDispatched());
    }
    public static DikidiCredential credential(CrmAccess access) {
        return new DikidiCredential(access.connectionId(), access.credentialVersion(), access.login(), access.secret());
    }
    /** Паузы сборке (server CrmTrafficStore) остаются в CRM-порту; клиент видит технический порт. */
    public static DikidiTrafficPort traffic(CrmTrafficPort pauses) {
        return new DikidiTrafficPort() {
            @Override public Pause current(UUID connectionId) {
                var value = pauses.current(connectionId);
                return new Pause(value.notBefore(), value.failures());
            }
            @Override public Pause block(UUID connectionId, Instant notBefore) {
                var value = pauses.block(connectionId, notBefore);
                return new Pause(value.notBefore(), value.failures());
            }
        };
    }
}
