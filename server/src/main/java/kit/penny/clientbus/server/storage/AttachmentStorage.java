package kit.penny.clientbus.server.storage;

import java.io.IOException;
import java.io.InputStream;

public interface AttachmentStorage {

    /**
     * Сохраняет бинарные данные в Object Storage.
     *
     * @param inputStream поток с бинарными данными
     * @param size размер файла в байтах
     * @param contentType MIME type
     */
    StoredAttachment store(
            InputStream inputStream,
            long size,
            String contentType
    ) throws IOException;

    /**
     * Открывает поток чтения объекта.
     */
    InputStream load(
            String storageKey
    ) throws IOException;

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
    ) throws IOException;
}