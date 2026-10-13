package hu.bmepilots.portal.study;

import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Versioned, original teaching content. Public records deliberately omit the answer key. */
@Component
public class StudyCatalog {
  public record Node(String id, double x, double y) {}

  public record Part(String id, String kind, String from, String to, double value, String unit) {}

  public record Circuit(List<Node> nodes, List<Part> parts) {}

  public record Field(
      String id,
      String label,
      String kind,
      String unit,
      List<String> options,
      String expected,
      String explanation) {
    PublicField publicView() {
      return new PublicField(id, label, kind, unit, options);
    }
  }

  public record PublicField(
      String id, String label, String kind, String unit, List<String> options) {}

  public record Lesson(String title, String formula, String text, String trap) {}

  public record Exercise(
      String id,
      String title,
      String topic,
      String difficulty,
      String prompt,
      String hint,
      Circuit circuit,
      Map<String, Double> constants,
      List<Field> fields,
      List<String> steps) {
    PublicExercise publicView() {
      return new PublicExercise(
          id,
          title,
          topic,
          difficulty,
          prompt,
          hint,
          circuit,
          constants,
          fields.stream().map(Field::publicView).toList());
    }
  }

  public record PublicExercise(
      String id,
      String title,
      String topic,
      String difficulty,
      String prompt,
      String hint,
      Circuit circuit,
      Map<String, Double> constants,
      List<PublicField> fields) {}

  public record Subject(
      String id, String title, String version, List<Lesson> lessons, List<Exercise> exercises) {}

  private final Subject subject;

  public StudyCatalog(ObjectMapper json) throws java.io.IOException {
    try (var stream = new ClassPathResource("study/engineering-v1.json").getInputStream()) {
      subject = json.readValue(stream, Subject.class);
    }
    Set<String> ids = new HashSet<>();
    for (var e : subject.exercises()) {
      if (!ids.add(e.id()) || e.fields().isEmpty())
        throw new IllegalStateException("Invalid study catalog");
      Set<String> fields = new HashSet<>();
      for (var f : e.fields()) {
        if (!fields.add(f.id()) || !StudyGrader.correct(f, f.expected(), e.constants()))
          throw new IllegalStateException("Invalid answer key: " + e.id() + "/" + f.id());
      }
    }
  }

  public Subject subject() {
    return subject;
  }

  public Exercise exercise(String id) {
    return subject.exercises().stream()
        .filter(e -> e.id().equals(id))
        .findFirst()
        .orElseThrow(hu.bmepilots.portal.common.error.ApiException::missing);
  }

  public Object publicView() {
    return Map.of(
        "id",
        subject.id(),
        "title",
        subject.title(),
        "version",
        subject.version(),
        "lessons",
        subject.lessons(),
        "exercises",
        subject.exercises().stream().map(Exercise::publicView).toList());
  }
}
