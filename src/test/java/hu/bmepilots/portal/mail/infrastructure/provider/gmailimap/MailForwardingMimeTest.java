package hu.bmepilots.portal.mail.infrastructure.provider.gmailimap;

import static org.assertj.core.api.Assertions.*;

import hu.bmepilots.portal.mail.application.MailForwardingService;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MailForwardingMimeTest {
  private MimeMessage message() throws Exception {
    var message = new MimeMessage(Session.getInstance(new Properties()));
    message.setFrom(new InternetAddress("sender@example.test"));
    message.setSubject("Ordinary incoming mail");
    message.setText("Original body");
    message.saveChanges();
    return message;
  }

  private boolean forwardable(MimeMessage message) throws Exception {
    return GmailImapMailProvider.convert(1, "provider-id", message, new Date()).forwardable();
  }

  @Test
  void forwardedCopiesVacationRepliesAndDeliveryReportsCannotCreateLoops() throws Exception {
    assertThat(forwardable(message())).isTrue();
    var marker = message();
    marker.setHeader("X-BME-Pilots-Forwarded", "1");
    assertThat(forwardable(marker)).isFalse();
    var vacation = message();
    vacation.setHeader("Auto-Submitted", "auto-replied; owner-email=recipient@example.test");
    assertThat(forwardable(vacation)).isFalse();
    var bounce = message();
    bounce.setHeader("Return-Path", "<>");
    assertThat(forwardable(bounce)).isFalse();
    var deliveryReport = message();
    deliveryReport.setHeader("Content-Type", "message/delivery-status");
    assertThat(forwardable(deliveryReport)).isFalse();
  }

  @Test
  void ordinarySharedSenderMailIsEligibleButItsForwardedCopiesAndAutomaticRepliesAreNot()
      throws Exception {
    for (var sender :
        java.util.List.of(
            "forwarding.shared.fixture@gmail.com",
            "FORWARDINGSHAREDFIXTURE+inbox@googlemail.com")) {
      var source = message();
      source.setFrom(new InternetAddress(sender));
      assertThat(forwardable(source)).isTrue();
      source.setHeader("X-BME-Pilots-Forwarded", "1");
      assertThat(forwardable(source)).isFalse();
      source.removeHeader("X-BME-Pilots-Forwarded");
      source.setHeader("Auto-Submitted", "auto-replied");
      assertThat(forwardable(source)).isFalse();
    }
  }

  @Test
  void legitimateAutomatedMailStillForwardsButUnknownReceivedDatesDoNotInventFreshness()
      throws Exception {
    var automated = message();
    automated.setHeader("Auto-Submitted", "auto-generated");
    assertThat(forwardable(automated)).isTrue();
    var noDate = GmailImapMailProvider.convert(1, "unknown-date", message(), null);
    assertThat(noDate.forwardable()).isFalse();
    var source = message();
    source.setSentDate(Date.from(Instant.parse("2099-01-01T00:00:00Z")));
    var received = Date.from(Instant.parse("2020-01-01T00:00:00Z"));
    assertThat(GmailImapMailProvider.convert(1, "old-mail", source, received).receivedAt())
        .isEqualTo(received.toInstant());
  }

  @Test
  void sharedGmailAliasesAreRecognizedWithoutRewritingOtherDomains() {
    assertThat(
            MailForwardingService.sameMailbox(
                "BME.Pilots2026+archive@googlemail.com", "bmepilots2026@gmail.com"))
        .isTrue();
    assertThat(MailForwardingService.sameMailbox(" shared@Example.test ", "SHARED@example.test"))
        .isTrue();
    assertThat(
            MailForwardingService.sameMailbox("bme.pilots@example.test", "bmepilots@example.test"))
        .isFalse();
    assertThat(MailForwardingService.sameMailbox("other@gmail.com", "bmepilots2026@gmail.com"))
        .isFalse();
  }
}
