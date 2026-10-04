package hu.bmepilots.portal.common.error;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handle(ApiException e) {
    return problem(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HandlerMethodValidationException.class,
    HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return problem(
        HttpStatus.BAD_REQUEST, "VALIDATION", "Please check the information you entered.");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> conflict(Exception e) {
    return problem(
        HttpStatus.CONFLICT,
        "CONFLICT",
        "The item already exists or is referenced by another record.");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> denied(Exception e) {
    return problem(
        HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e) {
    String reference = UUID.randomUUID().toString();
    LoggerFactory.getLogger(ApiErrors.class)
        .error("Request failed reference={} type={}", reference, e.getClass().getName());
    var p =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Reference: " + reference);
    p.setProperty("code", "INTERNAL_ERROR");
    return ResponseEntity.status(500).body(p);
  }

  private ResponseEntity<ProblemDetail> problem(HttpStatus s, String code, String detail) {
    var p = ProblemDetail.forStatusAndDetail(s, detail);
    p.setProperty("code", code);
    return ResponseEntity.status(s).body(p);
  }
}
