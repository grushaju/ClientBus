
package kit.penny.clientbus.server.exception;

public class ConversationGroupNameExistsException
        extends RuntimeException {

    public ConversationGroupNameExistsException() {
        super("Группа с таким названием уже существует");
    }
}
