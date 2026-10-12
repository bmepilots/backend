package hu.bmepilots.portal.mail.infrastructure.provider.gmailimap;

import static org.assertj.core.api.Assertions.*;

import hu.bmepilots.portal.mail.application.port.MailDelivery;
import hu.bmepilots.portal.mail.infrastructure.EmailSanitizer;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Adapter and protocol-outcome tests use synthetic credentials and an in-memory Transport. */
class GmailSmtpMailDeliveryTest {
  @TempDir Path temporary;
  FakeTransport transport;
  GmailSmtpMailDelivery sender;
  Path passwordFile;

  class FakeTransport extends Transport {
    MessagingException connectFailure;
    MessagingException sendFailure;
    boolean closeFails;
    int sends;
    int closes;
    String host;
    int port;
    Message sentMessage;
    Address[] recipients;

    FakeTransport() {
      super(Session.getInstance(new Properties()), null);
    }

    @Override
    public void connect(String host, int port, String user, String password)
        throws MessagingException {
      this.host = host;
      this.port = port;
      if (connectFailure != null) throw connectFailure;
      setConnected(true);
    }

    @Override
    public void sendMessage(Message message, Address[] addresses) throws MessagingException {
      sends++;
      sentMessage = message;
      recipients = addresses;
      if (sendFailure != null) throw sendFailure;
    }

    @Override
    public void close() throws MessagingException {
      closes++;
      setConnected(false);
      if (closeFails) throw new MessagingException("Simulated lost QUIT reply");
    }
  }

  @BeforeEach
  void setup() throws Exception {
    passwordFile = temporary.resolve("synthetic-app-password");
    Files.writeString(passwordFile, "synthetic-test-password");
    transport = new FakeTransport();
    sender =
        new GmailSmtpMailDelivery(
            "shared@example.test", passwordFile.toString(), new EmailSanitizer()) {
          @Override
          protected Transport transport(Session session) {
            return transport;
          }
        };
  }

  private MailDelivery.Delivery delivery() {
    return new MailDelivery.Delivery(
        UUID.randomUUID().toString(),
        "member@example.test",
        "Flight briefing",
        "original@example.test",
        "Original sender",
        LocalDateTime.of(2026, 10, 9, 12, 30),
        "Plain original briefing",
        "<p>HTML original briefing</p>",
        List.of(),
        "reply@example.test");
  }

  @Test
  void rebuildsPrivateSafeMimeWithOriginalContentAttachmentsAndStableId() throws Exception {
    Path attachment = temporary.resolve("original-file");
    byte[] original = new byte[] {0, 1, 2, 13, 10, 127, -1};
    Files.write(attachment, original);
    var fixture = delivery();
    var input =
        new MailDelivery.Delivery(
            fixture.id(),
            fixture.recipient(),
            "Briefing\r\nBcc: attacker@example.test",
            fixture.senderAddress(),
            "<script>Original</script>",
            fixture.receivedAt(),
            fixture.text(),
            "<p>HTML original briefing</p><script>bad()</script><img src='https://track.example.test/pixel'>",
            List.of(
                new MailDelivery.Attachment(
                    "checklist\r\nX-Injected: yes.txt", "message/rfc822", attachment)),
            fixture.replyTo());
    var message = sender.buildMessage(input);
    assertThat(message.getAllRecipients()).hasSize(1);
    assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress())
        .isEqualTo("member@example.test");
    assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();
    assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
    assertThat(((InternetAddress) message.getFrom()[0]).getAddress())
        .isEqualTo("shared@example.test");
    assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress())
        .isEqualTo("reply@example.test");
    assertThat(message.getHeader("X-BME-Pilots-Forwarded", null)).isEqualTo("1");
    assertThat(message.getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
    assertThat(message.getMessageID())
        .isEqualTo("<forward-" + fixture.id() + "@bmepilots2026.com>");
    assertThat(sender.buildMessage(input).getMessageID()).isEqualTo(message.getMessageID());
    assertThat(message.getHeader("Bcc")).isNull();
    assertThat(message.getHeader("X-Injected")).isNull();
    var mixed = (MimeMultipart) message.getContent();
    assertThat(mixed.getCount()).isEqualTo(2);
    var alternatives = (MimeMultipart) mixed.getBodyPart(0).getContent();
    assertThat(alternatives.getBodyPart(0).getContent().toString())
        .contains("Plain original briefing", "original@example.test");
    assertThat(alternatives.getBodyPart(1).getContent().toString())
        .contains("HTML original briefing")
        .doesNotContain("<script>", "<img", "track.example.test", "bad()");
    var file = mixed.getBodyPart(1);
    assertThat(file.getDisposition()).isEqualTo(Part.ATTACHMENT);
    assertThat(file.isMimeType("application/octet-stream")).isTrue();
    try (var inputStream = file.getInputStream()) {
      assertThat(inputStream.readAllBytes()).isEqualTo(original);
    }
    assertThat(file.getFileName()).doesNotContain("\r", "\n");
    var serialized = new ByteArrayOutputStream();
    message.writeTo(serialized);
    assertThat(serialized.toString(StandardCharsets.UTF_8))
        .doesNotContain("\r\nBcc:", "\r\nX-Injected:");
  }

  @Test
  void rejectsInjectedOrMultipleRecipientsAndFallsBackFromAnInvalidReplyTo() throws Exception {
    var fixture = delivery();
    for (String recipient :
        List.of(
            "member@example.test\r\nBcc: attacker@example.test",
            "first@example.test, second@example.test",
            "Group: first@example.test;")) {
      var input =
          new MailDelivery.Delivery(
              fixture.id(),
              recipient,
              fixture.subject(),
              fixture.senderAddress(),
              fixture.senderName(),
              fixture.receivedAt(),
              fixture.text(),
              fixture.html(),
              List.of());
      assertThatThrownBy(() -> sender.buildMessage(input)).isInstanceOf(AddressException.class);
    }
    var invalidReply =
        new MailDelivery.Delivery(
            fixture.id(),
            fixture.recipient(),
            fixture.subject(),
            fixture.senderAddress(),
            fixture.senderName(),
            fixture.receivedAt(),
            fixture.text(),
            fixture.html(),
            List.of(),
            "evil@example.test\r\nBcc: other@example.test");
    var message = sender.buildMessage(invalidReply);
    assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress())
        .isEqualTo("original@example.test");
    assertThat(message.getAllRecipients()).hasSize(1);
  }

  @Test
  void requiresVerifiedStartTlsAndKeepsOneEnvelopeRecipientEvenWhenQuitReplyIsLost()
      throws Exception {
    var properties = GmailSmtpMailDelivery.properties();
    assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
    assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("20000");
    assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("20000");
    transport.closeFails = true;
    assertThat(sender.deliver(delivery()).outcome()).isEqualTo(MailDelivery.Outcome.SENT);
    assertThat(transport.host).isEqualTo("smtp.gmail.com");
    assertThat(transport.port).isEqualTo(587);
    assertThat(transport.recipients).hasSize(1);
    assertThat(transport.recipients[0].toString()).isEqualTo("member@example.test");
    assertThat(transport.closes).isEqualTo(1);
  }

  @Test
  void safeConnectionFailuresRetryWhileAuthenticationAndPreparationFailuresStop() throws Exception {
    transport.connectFailure = new MessagingException("Synthetic secret must not escape");
    assertThat(sender.deliver(delivery()))
        .isEqualTo(
            new MailDelivery.Result(MailDelivery.Outcome.RETRYABLE, "SMTP_CONNECTION_FAILED"));
    assertThat(transport.sends).isZero();
    transport.connectFailure =
        new AuthenticationFailedException("Synthetic secret must not escape");
    assertThat(sender.deliver(delivery()))
        .isEqualTo(
            new MailDelivery.Result(MailDelivery.Outcome.FAILED, "SMTP_AUTHENTICATION_FAILED"));
    assertThat(transport.sends).isZero();
    Files.writeString(passwordFile, " ");
    assertThat(sender.deliver(delivery()))
        .isEqualTo(new MailDelivery.Result(MailDelivery.Outcome.FAILED, "SMTP_NOT_CONFIGURED"));
    assertThat(transport.sends).isZero();
  }

  @Test
  void dataTimeoutOrUnknownSendFailureIsUncertainButExplicitSmtpRejectionIsDefinite()
      throws Exception {
    transport.sendFailure = new MessagingException("Timed out after DATA may have been accepted");
    assertThat(sender.deliver(delivery()))
        .isEqualTo(new MailDelivery.Result(MailDelivery.Outcome.UNCERTAIN, "SMTP_OUTCOME_UNKNOWN"));
    transport.sendFailure = new SendFailedException("Unknown send phase");
    assertThat(sender.deliver(delivery()).outcome()).isEqualTo(MailDelivery.Outcome.UNCERTAIN);
    transport.sendFailure =
        new SMTPSendFailedException(".", 451, "Temporary rejection", null, null, null, null);
    assertThat(sender.deliver(delivery()))
        .isEqualTo(new MailDelivery.Result(MailDelivery.Outcome.RETRYABLE, "SMTP_REJECTED"));
    transport.sendFailure =
        new SMTPSendFailedException(".", 550, "Permanent rejection", null, null, null, null);
    assertThat(sender.deliver(delivery()))
        .isEqualTo(new MailDelivery.Result(MailDelivery.Outcome.FAILED, "SMTP_REJECTED"));
    transport.sendFailure =
        new SendFailedException(
            "Recipient rejected",
            null,
            null,
            null,
            new Address[] {new InternetAddress("member@example.test")});
    assertThat(sender.deliver(delivery()))
        .isEqualTo(new MailDelivery.Result(MailDelivery.Outcome.FAILED, "SMTP_RECIPIENT_REJECTED"));
    transport.sendFailure =
        new SMTPSendFailedException(
            ".",
            451,
            "Reported accepted address",
            null,
            new Address[] {new InternetAddress("member@example.test")},
            null,
            null);
    assertThat(sender.deliver(delivery()).outcome()).isEqualTo(MailDelivery.Outcome.UNCERTAIN);
    var recipient = new InternetAddress("member@example.test");
    transport.sendFailure =
        new SendFailedException(
            "RCPT rejected",
            new SMTPAddressFailedException(
                recipient, "RCPT TO:<member@example.test>", 451, "Try later"),
            null,
            new Address[] {recipient},
            null);
    assertThat(sender.deliver(delivery()))
        .isEqualTo(new MailDelivery.Result(MailDelivery.Outcome.RETRYABLE, "SMTP_REJECTED"));
    transport.sendFailure =
        new SendFailedException(
            "RCPT rejected",
            new SMTPAddressFailedException(
                recipient, "RCPT TO:<member@example.test>", 550, "Invalid address"),
            null,
            null,
            new Address[] {recipient});
    assertThat(sender.deliver(delivery()))
        .isEqualTo(new MailDelivery.Result(MailDelivery.Outcome.FAILED, "SMTP_REJECTED"));
    transport.sendFailure =
        new SendFailedException(
            "Unknown recipient outcome",
            new SMTPAddressFailedException(
                new InternetAddress("other@example.test"),
                "RCPT TO:<other@example.test>",
                451,
                "Mismatched address"),
            null,
            new Address[] {recipient},
            null);
    assertThat(sender.deliver(delivery()).outcome()).isEqualTo(MailDelivery.Outcome.UNCERTAIN);
    transport.sendFailure =
        new SendFailedException(
            "Unknown send phase",
            new SMTPAddressFailedException(recipient, "DATA", 451, "Unrecognized command"),
            null,
            new Address[] {recipient},
            null);
    assertThat(sender.deliver(delivery()).outcome()).isEqualTo(MailDelivery.Outcome.UNCERTAIN);
  }
}
