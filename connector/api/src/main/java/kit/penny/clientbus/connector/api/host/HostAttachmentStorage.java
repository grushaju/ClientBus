package kit.penny.clientbus.connector.api.host;

import java.io.InputStream;

/**
 * Host port for persistent attachment content. The host owns storage location and access checks;
 * platform downloads/uploads and connector temporary files stay with the connector.
 *
 * <p>13.3 (контракт вложений, п.7 плана): владение жизненным циклом сохранённого контента
 * остаётся за хостом. Контент, сохранённый коннектором (например, аватары платформы),
 * привязывается к записи хоста (ключ в {@code avatarUrl} клиентского аккаунта), и его
 * очисткой владеет сервис, удаляющий эту запись ({@code ClientService.clearClientAvatar}).
 * Разрыв/удаление канала запись клиентского аккаунта не удаляет, поэтому путь канала
 * намеренно не удаляет объекты хранилища — это сохранило бы висячие ссылки. Сбор
 * осиротевших объектов по жизненным циклу записей — объём 13.5.
 */
public interface HostAttachmentStorage {

    HostStoredAttachment store(
            InputStream content,
            String fileName,
            long size,
            String contentType
    );

    InputStream load(
            String storageKey
    );

    boolean exists(
            String storageKey
    );

    void delete(
            String storageKey
    );
}
