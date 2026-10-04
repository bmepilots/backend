package hu.bmepilots.portal.common.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested item was not found.");
  }

  public static ApiException conflict() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "CONFLICT",
        "The item changed in the meantime, or this operation is not allowed.");
  }
}
