package hu.bmepilots.portal.documents.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.documents.infrastructure.DocumentStorage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reclaims abandoned private uploads without exposing them as community posts. */
@Component
public class DocumentUploadCleanup {
  private final JdbcClient db;
  private final AuditService audit;
  private final DocumentStorage storage;
  private final TransactionTemplate transactions;

  public DocumentUploadCleanup(
      JdbcClient db,
      AuditService audit,
      DocumentStorage storage,
      PlatformTransactionManager manager) {
    this.db = db;
    this.audit = audit;
    this.storage = storage;
    this.transactions = new TransactionTemplate(manager);
  }

  private record Expired(String id, String storageKey) {}

  @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
  public void cleanup() {
    // Small transactions avoid keeping a large expired backlog locked during file deletion.
    for (int batch = 0; batch < 10; batch++) {
      int removed =
          transactions.execute(
              status -> {
                var expired =
                    db.sql(
                            "SELECT id,storage_key FROM document_uploads WHERE expires_at<=CURRENT_TIMESTAMP(6) ORDER BY expires_at,id LIMIT 200 FOR UPDATE SKIP LOCKED")
                        .query(Expired.class)
                        .list();
                for (var file : expired) {
                  db.sql("DELETE FROM document_uploads WHERE id=?").param(file.id()).update();
                  audit.recordAs(null, "DOCUMENT_UPLOAD_EXPIRED", "DOCUMENT_UPLOAD", file.id());
                }
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void afterCommit() {
                        expired.forEach(file -> storage.delete(file.storageKey()));
                      }
                    });
                return expired.size();
              });
      if (removed < 200) break;
    }
    // One SQL statement observes the staged-to-published transfer atomically. The 48-hour grace
    // protects live requests and also reclaims files left by a crash before metadata committed.
    storage.reconcile(
        Instant.now().minus(48, ChronoUnit.HOURS),
        key ->
            db.sql(
                    "SELECT EXISTS(SELECT 1 FROM document_uploads WHERE storage_key=? UNION ALL SELECT 1 FROM document_files WHERE storage_key=?)")
                .params(key, key)
                .query(Boolean.class)
                .single());
  }
}
