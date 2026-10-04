package hu.bmepilots.portal.mail.application.port;

import java.time.Instant;
import java.util.List;

/** Provider boundary: no Jakarta Mail/IMAP types cross this interface. */
public interface MailProvider {
  record Address(String type, String address, String name) {}

  record Attachment(String filename, String contentType, byte[] bytes) {}

  record Message(
      long uid,
      String providerId,
      String messageId,
      String subject,
      String senderAddress,
      String senderName,
      Instant receivedAt,
      String text,
      String html,
      List<Address> addresses,
      List<Attachment> attachments) {}

  record Failure(long uid, String code) {}

  record Batch(long uidValidity, long cursor, List<Message> messages, List<Failure> failures) {}

  void testConnection() throws Exception;

  Batch fetch(long uidValidity, long afterUid, List<Long> retryUids) throws Exception;
}
