package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.integration.dikidi.DikidiCredential;

import java.util.UUID;

/**
 * Host-порт технической версии учётных данных DIKIDI (13.4, привязка к каналу — 13.5).
 * Реализацию даёт сборка сервера: она резолвит подключение по аккаунту канала
 * (channelaccount → channel → workspace → активный DIKIDI-проект) и выдаёт новый
 * экземпляр credential на каждую операцию — секрет не копируется и не хранится
 * в коннекторе. Значение credentialVersion обязано совпадать с версией в
 * хранилище, иначе клиент не переиспользует сессию CRM.
 */
public interface DikidiTechnicalCredentials {
    DikidiCredential obtain(
            UUID channelAccountId
    );
}
