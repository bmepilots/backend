package hu.bmepilots.portal.mail.application.port;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** Outgoing mail boundary. An uncertain SMTP outcome must never be retried automatically. */
public interface MailDelivery {
  record Attachment(String filename, String contentType, Path path) {}

  record Delivery(
      String id,
      String recipient,
      String subject,
      String senderAddress,
      String senderName,
      LocalDateTime receivedAt,
      String text,
      String html,
      List<Attachment> attachments,
      String replyTo) {
    public Delivery(
        String id,
        String recipient,
        String subject,
        String senderAddress,
        String senderName,
        LocalDateTime receivedAt,
        String text,
        String html,
        List<Attachment> attachments) {
      this(
          id,
          recipient,
          subject,
          senderAddress,
          senderName,
          receivedAt,
          text,
          html,
          attachments,
          senderAddress);
    }
  }

  enum Outcome {
    SENT,
    RETRYABLE,
    FAILED,
    UNCERTAIN
  }

  record Result(Outcome outcome, String errorCode) {}

  boolean available();

  Result deliver(Delivery delivery);
}
