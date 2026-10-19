package kit.penny.clientbus.server.exception;

public class ConversationAssignmentConflictException extends RuntimeException {
    public ConversationAssignmentConflictException() {
        super("Conversation is already assigned to another employee");
    }
}
