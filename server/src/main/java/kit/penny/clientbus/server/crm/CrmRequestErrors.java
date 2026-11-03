package kit.penny.clientbus.server.crm;

import jakarta.validation.ConstraintViolationException;
import kit.penny.clientbus.server.exception.GlobalExceptionHandler.ErrorResponse;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;

@RestControllerAdvice(basePackageClasses = CrmController.class)
public class CrmRequestErrors {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "Проверьте параметры CRM."));
    }
    @ExceptionHandler({OptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<ErrorResponse> version(Exception error) {
        return ResponseEntity.status(409).body(new ErrorResponse("CRM_VERSION_CONFLICT", "Обновите данные CRM."));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> constraint(Exception error) {
        return ResponseEntity.status(409).body(new ErrorResponse("CRM_RESOURCE_IN_USE", "Изменение конфликтует с существующими связями CRM."));
    }
}
