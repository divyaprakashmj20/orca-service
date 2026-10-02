package com.lytspeed.orka.config;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(java.util.Map.of("message",e.getReason()==null?"Request could not be completed":e.getReason()));}
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<Void> conflict(RuntimeException exception){return ResponseEntity.status(409).build();}
}
