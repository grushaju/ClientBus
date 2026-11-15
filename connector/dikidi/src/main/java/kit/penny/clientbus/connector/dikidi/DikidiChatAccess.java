package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.integration.dikidi.DikidiClient;
import kit.penny.clientbus.integration.dikidi.DikidiFailure;
import kit.penny.clientbus.integration.dikidi.DikidiCredential;

import java.util.UUID;

/**
 * Проверка доступа к чатам DIKIDI до перехода канала в CONNECTED (13.4; с 13.5 —
 * поаккаунтно). Протокол зафиксирован в CRM_DIKIDI_PROTOCOL.md (C01/C02): сначала
 * визит страницы владелец-чата (без неё dialogs/list отвечает HTTP 400), затем сам
 * список диалогов. Оба шага идут через общий DikidiClient в профиле OWNER_CHAT:
 * та же сессия и тот же ограничитель, что у CRM. Успешный CRM-health проверкой
 * чатов не считается и не заменяет эту процедуру. Привязка (connection, company)
 * больше не конфигурационная: company — externalId аккаунта канала, подключение
 * резолвит host-порт credentials по channelAccountId.
 */
public final class DikidiChatAccess {
    public record Result(boolean available, String errorCode) {
        private static Result ok() { return new Result(true, null); }
        private static Result denied(String code) { return new Result(false, code); }
    }
    private final DikidiClient client;
    private final DikidiTechnicalCredentials credentials;
    private final HostChannelAccounts accounts;
    private final DikidiChatReads reads;
    public DikidiChatAccess(DikidiClient client, DikidiTechnicalCredentials credentials,
                            HostChannelAccounts accounts, DikidiChatReads reads) {
        this.client = client; this.credentials = credentials; this.accounts = accounts; this.reads = reads;
    }
    /** Дёшево и безопасно: только GET, никаких изменяющих обращений. */
    public Result verify(UUID channelAccountId) {
        String company = accounts.require(channelAccountId).externalId();
        if (company == null || company.isBlank()) return Result.denied("CHAT_NO_COMPANY");
        try (DikidiCredential credential = credentials.obtain(channelAccountId)) {
            return client.execute(credential, java.time.Instant.now().plusSeconds(60),
                    session -> probe(session, company));
        } catch (DikidiFailure failure) {
            return Result.denied("CHAT_" + failure.code().name());
        }
    }
    private Result probe(DikidiClient.Session session, String company) {
        try {
            reads.shell(session, company);
            reads.dialogs(session, company);
            return Result.ok();
        } catch (DikidiFailure failure) {
            // AUTH_REQUIRED выпускаем наружу: только так execute увидит сигнал и долечит
            // ровно одним пере-входом (с обязательным новым shell-визитом). Остальные
            // формы отказа — тот же сигнал, что и отсутствие доступа.
            if (failure.code() == DikidiFailure.Code.AUTH_REQUIRED) throw failure;
            return Result.denied("CHAT_" + failure.code().name());
        }
    }
}
