package hu.bmepilots.portal.documents.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** Runs before the shared generic exception handler, including multipart resolver errors. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DocumentUploadErrors {
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException ignored) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "Each file must be 50 MB or smaller; attach up to 5 files per post.");
    problem.setProperty("code", "UPLOAD_TOO_LARGE");
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
  }

  @ExceptionHandler(MultipartException.class)
  ResponseEntity<ProblemDetail> invalid(MultipartException ignored) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "The file upload could not be read. Please select the files and try again.");
    problem.setProperty("code", "VALIDATION");
    return ResponseEntity.badRequest().body(problem);
  }
}
