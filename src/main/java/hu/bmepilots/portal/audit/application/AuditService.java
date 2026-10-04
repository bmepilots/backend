package hu.bmepilots.portal.audit.application;

import hu.bmepilots.portal.common.security.CurrentUser;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JdbcClient db;

  public AuditService(JdbcClient db) {
    this.db = db;
  }

  public void record(String action, String type, String id) {
    recordAs(CurrentUser.optionalId(), action, type, id);
  }

  public void recordAs(String actor, String action, String type, String id) {
    db.sql("INSERT INTO audit_log(actor_user_id,action,entity_type,entity_id) VALUES (?,?,?,?)")
        .params(actor, action, type, id)
        .update();
  }

  public void request(String actor, String method, String route, int status, long duration) {
    db.sql(
            "INSERT INTO audit_log(actor_user_id,action,entity_type,entity_id,request_method,request_path,response_status,duration_ms) VALUES (?,'API_REQUEST','REQUEST','-',?,?,?,?)")
        .params(actor, method, route, status, duration)
        .update();
  }

  public record Entry(
      long id,
      String actorName,
      String action,
      String entityType,
      String entityId,
      LocalDateTime createdAt,
      String requestMethod,
      String requestPath,
      Integer responseStatus,
      Long durationMs) {}

  public List<Entry> list(int page) {
    return list(page, false);
  }

  public List<Entry> list(int page, boolean requests) {
    return db.sql(
            "SELECT a.id, COALESCE(u.display_name,'Anonymous / system') actor_name,a.action,a.entity_type,a.entity_id,a.created_at,a.request_method,a.request_path,a.response_status,a.duration_ms FROM audit_log a LEFT JOIN users u ON u.id=a.actor_user_id WHERE "
                + (requests ? "a.action='API_REQUEST'" : "a.action<>'API_REQUEST'")
                + " ORDER BY a.id DESC LIMIT 50 OFFSET ?")
        .param(Math.max(0, Math.min(page, 10000)) * 50)
        .query(Entry.class)
        .list();
  }
}
