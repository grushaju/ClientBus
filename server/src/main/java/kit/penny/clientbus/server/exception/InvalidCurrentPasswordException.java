package kit.penny.clientbus.server.exception;

import org.springframework.security.core.AuthenticationException;

public class InvalidCurrentPasswordException
        extends AuthenticationException {

    public InvalidCurrentPasswordException() {
        super("Invalid current password");
    }
}