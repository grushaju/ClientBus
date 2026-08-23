package kit.penny.clientbus.server.storage;

import java.io.InputStream;

public interface IAttachmentStorage {

    /**
     * Сохраняет бинарные данные в Object Storage.
     *
     * @param inputStream поток с бинарными данными
     * @param size размер файла в байтах
     * @param contentType MIME type
     */
    StoredAttachmentMetadata store(
            InputStream inputStream,
            String fileName,
            long size,
            String contentType
    );

    /**
     * Открывает поток чтения объекта.
     */
    InputStream load(
            String storageKey
    );

    /**
     * Проверяет существование объекта.
     */
    boolean exists(
            String storageKey
    );

    /**
     * Удаляет объект.
     */
    void delete(
            String storageKey
    );
}