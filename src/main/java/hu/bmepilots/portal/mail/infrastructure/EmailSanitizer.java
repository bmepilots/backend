package hu.bmepilots.portal.mail.infrastructure;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class EmailSanitizer {
  public String sanitize(String html) {
    var safe =
        Safelist.basic()
            .addTags("table", "thead", "tbody", "tr", "td", "th", "hr")
            .removeTags("img")
            .addEnforcedAttribute("a", "target", "_blank")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer");
    return Jsoup.clean(html, safe);
  }
}
