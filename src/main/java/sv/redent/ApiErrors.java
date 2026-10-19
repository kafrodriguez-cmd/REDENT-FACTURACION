package sv.redent;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(SchemaService.ValidationException.class)
    ResponseEntity<?> schema(SchemaService.ValidationException e) { return ResponseEntity.unprocessableEntity().body(Map.of("mensaje",e.getMessage(),"errores",e.errors)); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> bad(IllegalArgumentException e) {return ResponseEntity.badRequest().body(Map.of("mensaje",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> state(IllegalStateException e) {return ResponseEntity.status(409).body(Map.of("mensaje",e.getMessage()));}
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> duplicate(DataIntegrityViolationException e) {return ResponseEntity.status(409).body(Map.of("mensaje","Código, identificación o solicitud duplicada. Recargue y revise el registro existente"));}
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> denied() {return ResponseEntity.status(403).body(Map.of("mensaje","Su cuenta no tiene permiso para esta operación"));}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> big() {return ResponseEntity.status(413).body(Map.of("mensaje","Archivo supera el límite de 5 MB"));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Fallo interno de tipo {}",e.getClass().getName());
        return ResponseEntity.internalServerError().body(Map.of("mensaje","No se completó la operación. Revise los registros del servidor"));
    }
}
