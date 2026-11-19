package kit.penny.clientbus.server.exception;

/**
 * Client нельзя удалить, пока с ним связаны учётные записи чатов.
 *
 * Без этого отказа удаление падало на внешнем ключе clientaccount_client_fk и оператор
 * получал сырое нарушение целостности вместо причины: снять привязку можно только рукой,
 * поэтому отказ обязан называть, сколько привязок мешает.
 */
public class ClientInUseException extends RuntimeException {

    public ClientInUseException(String message) {
        super(message);
    }
}
