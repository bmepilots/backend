package hu.bmepilots.portal.mail.infrastructure.provider.gmailimap;

import static org.assertj.core.api.Assertions.*;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MimeParserTest {
  @Test
  void readsMultipartAlternativesAndAttachmentWithoutProtocolObjects() throws Exception {
    var mime = new MimeMessage(Session.getInstance(new Properties()));
    mime.setFrom(new InternetAddress("sender@example.test", "Sender"));
    mime.setRecipients(Message.RecipientType.TO, "crew@example.test");
    mime.setSubject("Flight briefing", "UTF-8");
    var multipart = new MimeMultipart();
    var text = new MimeBodyPart();
    text.setText("Plain briefing", "UTF-8");
    multipart.addBodyPart(text);
    var html = new MimeBodyPart();
    html.setContent("<p>HTML briefing</p>", "text/html; charset=UTF-8");
    multipart.addBodyPart(html);
    var attachment = new MimeBodyPart();
    attachment.setText("Attachment content", "UTF-8");
    attachment.setFileName("checklist.txt");
    attachment.setDisposition(Part.ATTACHMENT);
    multipart.addBodyPart(attachment);
    mime.setContent(multipart);
    mime.saveChanges();
    var result = GmailImapMailProvider.convert(12, "provider-12", mime, new Date());
    assertThat(result.uid()).isEqualTo(12);
    assertThat(result.text()).contains("Plain briefing");
    assertThat(result.html()).contains("HTML briefing");
    assertThat(result.attachments()).hasSize(1);
    assertThat(result.addresses()).anyMatch(a -> a.address().equals("crew@example.test"));
  }

  @Test
  void deeplyNestedMessagesAreRejected() throws Exception {
    var mime = new MimeMessage(Session.getInstance(new Properties()));
    var root = new MimeMultipart();
    var current = root;
    for (int i = 0; i < 12; i++) {
      var next = new MimeMultipart();
      var part = new MimeBodyPart();
      part.setContent(next);
      current.addBodyPart(part);
      current = next;
    }
    var leaf = new MimeBodyPart();
    leaf.setText("Too deep");
    current.addBodyPart(leaf);
    mime.setContent(root);
    mime.saveChanges();
    assertThatThrownBy(() -> GmailImapMailProvider.convert(1, null, mime, new Date()))
        .hasMessage("MIME_LIMIT");
  }
}
