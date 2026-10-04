package hu.bmepilots.portal.calendar.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.common.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarService {
  private final JdbcClient db;
  private final AuditService audit;

  public CalendarService(JdbcClient db, AuditService audit) {
    this.db = db;
    this.audit = audit;
  }

  public enum EventType {
    EXAM,
    EVENT,
    DEADLINE
  }

  public record Change(
      @NotBlank @Size(max = 180) String title,
      @NotNull @Size(max = 10000) String description,
      @NotNull EventType type,
      @NotNull LocalDateTime startsAt,
      LocalDateTime endsAt,
      boolean allDay,
      @NotNull @Size(max = 200) String location,
      @NotNull @Min(0) Long version) {}

  public record Event(
      String id,
      String title,
      String description,
      EventType type,
      LocalDateTime startsAt,
      LocalDateTime endsAt,
      boolean allDay,
      String location,
      String authorId,
      String authorName,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long version) {}

  private static final String VIEW =
      "SELECT e.id,e.title,e.description,e.event_type type,e.starts_at,e.ends_at,e.all_day,e.location,e.created_by author_id,u.display_name author_name,e.created_at,e.updated_at,e.version FROM calendar_events e JOIN users u ON u.id=e.created_by";

  public List<Event> list(LocalDate from, LocalDate to) {
    long days = ChronoUnit.DAYS.between(from, to);
    if (days < 1 || days > 366)
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "CALENDAR_RANGE", "Choose a calendar range of 1 to 366 days.");
    // All-day entries with no end occupy one date. Timed entries with no end are instants.
    var events =
        db.sql(
                VIEW
                    + " WHERE e.starts_at < :until AND (e.ends_at > :from OR (e.ends_at IS NULL AND ((e.all_day=TRUE AND DATE_ADD(e.starts_at, INTERVAL 1 DAY) > :from) OR (e.all_day=FALSE AND e.starts_at >= :from)))) ORDER BY e.starts_at,e.all_day DESC,e.id LIMIT 2001")
            .param("from", from.atStartOfDay())
            .param("until", to.atStartOfDay())
            .query(Event.class)
            .list();
    if (events.size() > 2000)
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "CALENDAR_RANGE",
          "Too many events. Choose a shorter calendar range.");
    return events;
  }

  public Event get(String id) {
    return db.sql(VIEW + " WHERE e.id=?")
        .param(id)
        .query(Event.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  private void validate(Change change) {
    if (change.endsAt() != null && !change.endsAt().isAfter(change.startsAt()))
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "CALENDAR_RANGE", "The end must be after the start.");
    if (change.allDay()
        && (!change.startsAt().toLocalTime().equals(LocalTime.MIDNIGHT)
            || (change.endsAt() != null
                && !change.endsAt().toLocalTime().equals(LocalTime.MIDNIGHT))))
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "CALENDAR_RANGE",
          "All-day event dates must start at midnight; the end date is exclusive.");
  }

  private void requireOwnerOrAdmin(Event event) {
    var actor = CurrentUser.principal();
    if (!actor.role().equals("ADMIN") && !actor.id().equals(event.authorId()))
      throw new AccessDeniedException("Only the author or an administrator can manage this event.");
  }

  @Transactional
  public Event save(String id, Change change) {
    validate(change);
    boolean creating = id == null;
    if (creating) {
      id = UUID.randomUUID().toString();
      db.sql(
              "INSERT INTO calendar_events(id,title,description,event_type,starts_at,ends_at,all_day,location,created_by,updated_by) VALUES (:id,:title,:description,:type,:starts,:ends,:allDay,:location,:actor,:actor)")
          .param("id", id)
          .param("title", change.title().trim())
          .param("description", change.description().trim())
          .param("type", change.type().name())
          .param("starts", change.startsAt())
          .param("ends", change.endsAt())
          .param("allDay", change.allDay())
          .param("location", change.location().trim())
          .param("actor", CurrentUser.id())
          .update();
    } else {
      requireOwnerOrAdmin(get(id));
      int changed =
          db.sql(
                  "UPDATE calendar_events SET title=:title,description=:description,event_type=:type,starts_at=:starts,ends_at=:ends,all_day=:allDay,location=:location,updated_by=:actor,updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=:id AND version=:version")
              .param("id", id)
              .param("version", change.version())
              .param("title", change.title().trim())
              .param("description", change.description().trim())
              .param("type", change.type().name())
              .param("starts", change.startsAt())
              .param("ends", change.endsAt())
              .param("allDay", change.allDay())
              .param("location", change.location().trim())
              .param("actor", CurrentUser.id())
              .update();
      if (changed != 1) throw ApiException.conflict();
    }
    audit.record(
        creating ? "CALENDAR_EVENT_CREATED" : "CALENDAR_EVENT_UPDATED", "CALENDAR_EVENT", id);
    return get(id);
  }

  @Transactional
  public void delete(String id, long version) {
    requireOwnerOrAdmin(get(id));
    if (db.sql("DELETE FROM calendar_events WHERE id=? AND version=?").params(id, version).update()
        != 1) throw ApiException.conflict();
    audit.record("CALENDAR_EVENT_DELETED", "CALENDAR_EVENT", id);
  }
}
