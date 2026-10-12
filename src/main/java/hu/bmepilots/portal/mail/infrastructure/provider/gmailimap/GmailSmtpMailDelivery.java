package hu.bmepilots.portal.mail.infrastructure.provider.gmailimap;

import hu.bmepilots.portal.mail.application.port.MailDelivery;
import hu.bmepilots.portal.mail.infrastructure.EmailSanitizer;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Properties;
import org.jsoup.nodes.Entities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Gmail submission with required STARTTLS and no clear-text authentication fallback. */
@Component
public class GmailSmtpMailDelivery implements MailDelivery {
  private final String username;
  private final String passwordFile;
  private final EmailSanitizer sanitizer;

  public GmailSmtpMailDelivery(
      @Value("${portal.mail.username}") String username,
      @Value("${portal.mail.password-file}") String passwordFile,
      EmailSanitizer sanitizer) {
    this.username = username;
    this.passwordFile = passwordFile;
    this.sanitizer = sanitizer;
  }

  @Override
  public boolean available() {
    if (username.isBlank() || passwordFile.isBlank()) return false;
    try {
      return Files.isRegularFile(Path.of(passwordFile)) && Files.isReadable(Path.of(passwordFile));
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static Properties properties() {
    var properties = new Properties();
    properties.setProperty("mail.smtp.auth", "true");
    properties.setProperty("mail.smtp.starttls.enable", "true");
    properties.setProperty("mail.smtp.starttls.required", "true");
    properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
    properties.setProperty("mail.smtp.connectiontimeout", "10000");
    properties.setProperty("mail.smtp.timeout", "20000");
    properties.setProperty("mail.smtp.writetimeout", "20000");
    properties.setProperty("mail.smtp.sendpartial", "false");
    // Acceptance of DATA is authoritative even if the subsequent QUIT reply is lost.
    properties.setProperty("mail.smtp.quitwait", "false");
    return properties;
  }

  /** Rebuild a safe forwarded message; never copy source routing/authentication headers. */
  public MimeMessage buildMessage(Delivery delivery) throws Exception {
    var session = Session.getInstance(properties());
    String messageId =
        "<forward-" + java.util.UUID.fromString(delivery.id()) + "@bmepilots2026.com>";
    var message =
        new MimeMessage(session) {
          @Override
          protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", messageId);
          }
        };
    message.setFrom(address(username));
    message.setRecipient(Message.RecipientType.TO, address(delivery.recipient()));
    message.setSubject("[BME Pilots] " + header(delivery.subject()), "UTF-8");
    message.setSentDate(new Date());
    message.setHeader("Auto-Submitted", "auto-generated");
    message.setHeader("X-Auto-Response-Suppress", "All");
    message.setHeader("X-BME-Pilots-Forwarded", "1");
    try {
      message.setReplyTo(new Address[] {address(delivery.replyTo())});
    } catch (AddressException ignored) {
      try {
        message.setReplyTo(new Address[] {address(delivery.senderAddress())});
      } catch (AddressException invalidSender) {
        /* Malformed source addresses remain body metadata. */
      }
    }

    String original = header(delivery.senderName()) + " <" + header(delivery.senderAddress()) + ">";
    String intro =
        "Forwarded from the BME Pilots shared inbox.\nFrom: "
            + original
            + "\nReceived (UTC): "
            + delivery.receivedAt()
            + "\nSubject: "
            + header(delivery.subject())
            + "\n\n";
    var alternatives = new MimeMultipart("alternative");
    var plain = new MimeBodyPart();
    plain.setText(intro + delivery.text(), "UTF-8");
    alternatives.addBodyPart(plain);
    if (!delivery.html().isBlank()) {
      var html = new MimeBodyPart();
      html.setContent(
          "<p>Forwarded from the BME Pilots shared inbox.</p><p>From: "
              + Entities.escape(original)
              + "<br>Received (UTC): "
              + delivery.receivedAt()
              + "<br>Subject: "
              + Entities.escape(header(delivery.subject()))
              + "</p><hr>"
              + sanitizer.sanitize(delivery.html()),
          "text/html; charset=UTF-8");
      alternatives.addBodyPart(html);
    }
    var mixed = new MimeMultipart("mixed");
    var body = new MimeBodyPart();
    body.setContent(alternatives);
    mixed.addBodyPart(body);
    for (var attachment : delivery.attachments()) {
      if (!Files.isRegularFile(attachment.path()) || !Files.isReadable(attachment.path()))
        throw new java.io.IOException("ATTACHMENT_UNAVAILABLE");
      var part = new MimeBodyPart();
      // Treat every stored file as an attachment, never reinterpret hostile source MIME types.
      part.attachFile(attachment.path().toFile(), "application/octet-stream", "base64");
      part.setFileName(header(attachment.filename()));
      part.setDisposition(Part.ATTACHMENT);
      mixed.addBodyPart(part);
    }
    message.setContent(mixed);
    message.saveChanges();
    return message;
  }

  private static String header(String value) {
    return value == null ? "" : value.replaceAll("[\\r\\n]", " ");
  }

  private static InternetAddress address(String value) throws AddressException {
    if (value == null || value.contains("\r") || value.contains("\n")) throw new AddressException();
    var address = new InternetAddress(value, true);
    address.validate();
    if (address.isGroup() || !address.getAddress().contains("@")) throw new AddressException();
    return address;
  }

  @Override
  public Result deliver(Delivery delivery) {
    MimeMessage message;
    String password;
    try {
      if (!available()) return new Result(Outcome.FAILED, "SMTP_NOT_CONFIGURED");
      password = Files.readString(Path.of(passwordFile)).trim();
      if (password.isBlank()) return new Result(Outcome.FAILED, "SMTP_NOT_CONFIGURED");
      message = buildMessage(delivery);
    } catch (Exception e) {
      return new Result(Outcome.FAILED, "DELIVERY_PREPARATION_FAILED");
    }

    Transport transport = null;
    try {
      transport = transport(Session.getInstance(properties()));
      transport.connect("smtp.gmail.com", 587, username, password);
    } catch (AuthenticationFailedException e) {
      close(transport);
      return new Result(Outcome.FAILED, "SMTP_AUTHENTICATION_FAILED");
    } catch (Exception e) {
      close(transport);
      return new Result(Outcome.RETRYABLE, "SMTP_CONNECTION_FAILED");
    }
    try {
      transport.sendMessage(message, message.getRecipients(Message.RecipientType.TO));
      return new Result(Outcome.SENT, null);
    } catch (SendFailedException e) {
      // A rejected address/explicit SMTP negative response proves no recipient accepted the mail.
      if (e.getValidSentAddresses() == null || e.getValidSentAddresses().length == 0) {
        if (e instanceof org.eclipse.angus.mail.smtp.SMTPSendFailedException smtp
            && smtp.getReturnCode() >= 400
            && smtp.getReturnCode() <= 599)
          return new Result(
              smtp.getReturnCode() < 500 ? Outcome.RETRYABLE : Outcome.FAILED, "SMTP_REJECTED");
        // Angus wraps RCPT TO rejections in SendFailedException before it issues DATA.
        // Match the only envelope recipient; unknown phases/address mixtures remain uncertain.
        Exception nested = e.getNextException();
        for (int depth = 0; nested != null && depth < 10; depth++) {
          if (nested instanceof org.eclipse.angus.mail.smtp.SMTPAddressFailedException rejected
              && rejected.getCommand() != null
              && rejected.getCommand().startsWith("RCPT TO:")
              && rejected.getAddress() != null
              && delivery.recipient().equalsIgnoreCase(rejected.getAddress().getAddress())
              && rejected.getReturnCode() >= 400
              && rejected.getReturnCode() <= 599)
            return new Result(
                rejected.getReturnCode() < 500 ? Outcome.RETRYABLE : Outcome.FAILED,
                "SMTP_REJECTED");
          nested =
              nested instanceof MessagingException messaging ? messaging.getNextException() : null;
        }
        if (e.getInvalidAddresses() != null && e.getInvalidAddresses().length > 0)
          return new Result(Outcome.FAILED, "SMTP_RECIPIENT_REJECTED");
      }
      return new Result(Outcome.UNCERTAIN, "SMTP_OUTCOME_UNKNOWN");
    } catch (Exception e) {
      return new Result(Outcome.UNCERTAIN, "SMTP_OUTCOME_UNKNOWN");
    } finally {
      close(transport);
    }
  }

  protected Transport transport(Session session) throws MessagingException {
    return session.getTransport("smtp");
  }

  private static void close(Transport transport) {
    if (transport != null)
      try {
        transport.close();
      } catch (MessagingException ignored) {
        /* DATA result already recorded. */
      }
  }
}
