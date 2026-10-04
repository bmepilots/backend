package hu.bmepilots.portal.dashboard.api;

import hu.bmepilots.portal.announcement.application.AnnouncementService;
import hu.bmepilots.portal.calendar.application.CalendarService;
import hu.bmepilots.portal.common.security.CurrentUser;
import hu.bmepilots.portal.documents.application.DocumentsService;
import hu.bmepilots.portal.links.application.LinksService;
import hu.bmepilots.portal.mail.application.MailService;
import hu.bmepilots.portal.user.application.UserService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DashboardController {
  private final AnnouncementService announcements;
  private final DocumentsService documents;
  private final CalendarService calendar;
  private final LinksService links;
  private final MailService mail;
  private final UserService users;

  public DashboardController(
      AnnouncementService announcements,
      DocumentsService documents,
      CalendarService calendar,
      LinksService links,
      MailService mail,
      UserService users) {
    this.announcements = announcements;
    this.documents = documents;
    this.calendar = calendar;
    this.links = links;
    this.mail = mail;
    this.users = users;
  }

  @GetMapping("/dashboard")
  Object dashboard() {
    return Map.of(
        "announcements",
        announcements.list(0).stream().limit(3).toList(),
        "documents",
        documents.list("", 0).stream().limit(4).toList(),
        "upcomingEvents",
        calendar
            .list(
                java.time.LocalDate.now(java.time.ZoneId.of("Europe/Budapest")),
                java.time.LocalDate.now(java.time.ZoneId.of("Europe/Budapest")).plusDays(31))
            .stream()
            .limit(5)
            .toList(),
        "links",
        links.list().stream().limit(4).toList(),
        "unreadMailCount",
        mail.unread(CurrentUser.id()));
  }

  @GetMapping("/admin/dashboard")
  Object admin() {
    return Map.of("pendingRegistrations", users.pendingCount());
  }
}
