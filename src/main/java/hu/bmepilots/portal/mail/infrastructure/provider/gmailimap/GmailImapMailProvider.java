package hu.bmepilots.portal.mail.infrastructure.provider.gmailimap;

import hu.bmepilots.portal.mail.application.port.MailProvider;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GmailImapMailProvider implements MailProvider {
  private static final int MAX_BYTES = 15 * 1024 * 1024;
  private final String username, passwordFile;
  private final int initialDays;

  public GmailImapMailProvider(
      @Value("${portal.mail.username}") String username,
      @Value("${portal.mail.password-file}") String passwordFile,
      @Value("${portal.mail.initial-days}") int initialDays) {
    this.username = username;
    this.passwordFile = passwordFile;
    this.initialDays = initialDays;
  }

  private Session session() {
    Properties p = new Properties();
    p.setProperty("mail.imaps.connectiontimeout", "10000");
    p.setProperty("mail.imaps.timeout", "20000");
    p.setProperty("mail.imaps.writetimeout", "20000");
    p.setProperty("mail.imaps.ssl.checkserveridentity", "true");
    p.setProperty("mail.imaps.peek", "true");
    return Session.getInstance(p);
  }

  private Store connect(Session session) throws Exception {
    if (username.isBlank() || passwordFile.isBlank())
      throw new IllegalStateException("MAIL_NOT_CONFIGURED");
    String password = Files.readString(Path.of(passwordFile)).trim();
    if (password.isBlank()) throw new IllegalStateException("MAIL_NOT_CONFIGURED");
    Store store = session.getStore("imaps");
    try {
      store.connect("imap.gmail.com", 993, username, password);
      return store;
    } catch (Exception e) {
      store.close();
      throw e;
    }
  }

  @Override
  public void testConnection() throws Exception {
    try (Store store = connect(session())) {
      if (!store.isConnected()) throw new IOException("Disconnected");
    }
  }

  @Override
  public Batch fetch(long previousValidity, long afterUid, List<Long> retryUids) throws Exception {
    Session session = session();
    try (Store store = connect(session)) {
      IMAPFolder folder = (IMAPFolder) store.getFolder("INBOX");
      folder.open(Folder.READ_ONLY);
      try {
        long validity = folder.getUIDValidity();
        long cursor = validity == previousValidity ? afterUid : 0;
        long end = Math.min(cursor + 100, folder.getUIDNext() - 1);
        var messages = new ArrayList<MailProvider.Message>();
        var failures = new ArrayList<Failure>();
        var selected = new LinkedHashMap<Long, jakarta.mail.Message>();
        if (validity == previousValidity)
          for (long uid : retryUids.stream().limit(2).toList()) {
            var m = folder.getMessageByUID(uid);
            if (m != null) selected.put(uid, m);
            else failures.add(new Failure(uid, "SOURCE_MISSING"));
          }
        if (end > cursor)
          for (var m : folder.getMessagesByUID(cursor + 1, end)) {
            if (m == null) continue;
            selected.put(folder.getUID(m), m);
            if (selected.size() >= 5) break;
          }
        long nextCursor = cursor;
        Instant cutoff = Instant.now().minus(Duration.ofDays(initialDays));
        for (var entry : selected.entrySet()) {
          long uid = entry.getKey();
          var source = entry.getValue();
          try {
            Date date = source.getReceivedDate();
            if (date == null || !date.toInstant().isBefore(cutoff)) {
              if (source.getSize() > MAX_BYTES) throw new IOException("MESSAGE_TOO_LARGE");
              var out = new LimitedOutputStream(MAX_BYTES);
              source.writeTo(out);
              var mime = new MimeMessage(session, new ByteArrayInputStream(out.toByteArray()));
              messages.add(convert(uid, providerId(folder, uid), mime, date));
            }
          } catch (Exception e) {
            failures.add(new Failure(uid, "MESSAGE_PROCESSING_FAILED"));
          }
          if (uid > cursor) nextCursor = Math.max(nextCursor, uid);
        }
        if (selected.isEmpty()) nextCursor = Math.max(cursor, end);
        return new Batch(validity, nextCursor, List.copyOf(messages), List.copyOf(failures));
      } finally {
        folder.close(false);
      }
    }
  }

  private String providerId(IMAPFolder folder, long uid) throws MessagingException {
    return (String)
        folder.doCommand(
            protocol -> {
              var responses = protocol.command("UID FETCH " + uid + " (X-GM-MSGID)", null);
              var pattern = Pattern.compile("X-GM-MSGID ([0-9]+)");
              for (var response : responses) {
                var matcher = pattern.matcher(response.toString());
                if (matcher.find()) return matcher.group(1);
              }
              return null;
            });
  }

  static MailProvider.Message convert(long uid, String providerId, MimeMessage m, Date date)
      throws Exception {
    var content = new Content();
    readPart(m, content, 0);
    var from = m.getFrom();
    var sender =
        from != null && from.length > 0
            ? address("FROM", from[0])
            : new MailProvider.Address("FROM", "", "");
    var addresses = new ArrayList<MailProvider.Address>();
    add(addresses, "TO", m.getRecipients(jakarta.mail.Message.RecipientType.TO));
    add(addresses, "CC", m.getRecipients(jakarta.mail.Message.RecipientType.CC));
    add(addresses, "REPLY_TO", m.getReplyTo());
    return new MailProvider.Message(
        uid,
        providerId,
        clip(m.getMessageID(), 998),
        clip(m.getSubject(), 998),
        sender.address(),
        sender.name(),
        date == null ? Instant.now() : date.toInstant(),
        content.text.toString(),
        content.html.toString(),
        addresses,
        content.attachments);
  }

  private static void add(
      List<MailProvider.Address> target, String type, jakarta.mail.Address[] values) {
    if (values != null)
      for (var a : values) {
        if (target.size() >= 200) break;
        target.add(address(type, a));
      }
  }

  private static MailProvider.Address address(String type, jakarta.mail.Address a) {
    if (a instanceof InternetAddress i)
      return new MailProvider.Address(type, clip(i.getAddress(), 254), clip(i.getPersonal(), 254));
    return new MailProvider.Address(type, clip(a.toString(), 254), "");
  }

  private static String clip(String s, int max) {
    return s == null ? "" : s.substring(0, Math.min(s.length(), max));
  }

  private static class Content {
    StringBuilder text = new StringBuilder(), html = new StringBuilder();
    List<Attachment> attachments = new ArrayList<>();
    int parts = 0;
  }

  private static void readPart(Part part, Content c, int depth) throws Exception {
    if (depth > 10 || ++c.parts > 100) throw new IOException("MIME_LIMIT");
    if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
      if (c.attachments.size() >= 20) throw new IOException("ATTACHMENT_LIMIT");
      try (var input = part.getInputStream()) {
        var bytes = input.readNBytes(10 * 1024 * 1024 + 1);
        if (bytes.length > 10 * 1024 * 1024) throw new IOException("ATTACHMENT_TOO_LARGE");
        String name = part.getFileName();
        if (name == null) name = "attachment";
        c.attachments.add(
            new Attachment(
                clip(MimeUtility.decodeText(name).replaceAll("[\\r\\n]", ""), 255),
                clip(part.getContentType().split(";")[0], 150),
                bytes));
      }
    } else if (part.isMimeType("multipart/*")) {
      var multi = (Multipart) part.getContent();
      for (int i = 0; i < multi.getCount(); i++) readPart(multi.getBodyPart(i), c, depth + 1);
    } else if (part.isMimeType("text/plain"))
      c.text.append(clip((String) part.getContent(), 500000));
    else if (part.isMimeType("text/html")) c.html.append(clip((String) part.getContent(), 500000));
    if (c.text.length() > 1000000 || c.html.length() > 1000000) throw new IOException("BODY_LIMIT");
  }

  private static class LimitedOutputStream extends ByteArrayOutputStream {
    private final int max;

    LimitedOutputStream(int max) {
      this.max = max;
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
      if (count + len > max) throw new IllegalArgumentException("MESSAGE_TOO_LARGE");
      super.write(b, off, len);
    }

    @Override
    public synchronized void write(int b) {
      if (count >= max) throw new IllegalArgumentException("MESSAGE_TOO_LARGE");
      super.write(b);
    }
  }
}
