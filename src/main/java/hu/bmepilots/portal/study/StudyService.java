package hu.bmepilots.portal.study;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class StudyService {
  private final StudyCatalog catalog;
  private final JdbcClient db;
  private final ObjectMapper json;
  private final AuditService audit;

  public StudyService(StudyCatalog catalog, JdbcClient db, ObjectMapper json, AuditService audit) {
    this.catalog = catalog;
    this.db = db;
    this.json = json;
    this.audit = audit;
  }

  public record Submission(
      @NotBlank @Size(max = 80) String exerciseId,
      @NotNull @Size(max = 20)
          Map<@NotBlank @Size(max = 50) String, @NotNull @Size(max = 256) String> answers) {}

  public record Attempt(
      @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String requestId,
      @NotBlank @Size(max = 80) String subjectId,
      @NotBlank @Size(max = 40) String version,
      @NotNull @Pattern(regexp = "PRACTICE|MOCK") String mode,
      @NotNull @Size(min = 1, max = 6) List<@NotNull @Valid Submission> submissions) {}

  public record FieldResult(
      String id, String answer, boolean correct, String expected, String explanation) {}

  public record ExerciseResult(
      String exerciseId,
      int earned,
      int possible,
      List<FieldResult> fields,
      List<String> steps,
      StudyCatalog.PublicExercise question) {}

  public record Result(String id, int earned, int possible, List<ExerciseResult> exercises) {}

  public record Stored(String requestJson, String resultJson) {}

  @Transactional
  public Result submit(Attempt attempt) {
    String user = CurrentUser.id();
    // Serialize submissions from one member; retries cannot count twice, including concurrent
    // requests.
    db.sql("SELECT id FROM users WHERE id=? FOR UPDATE").param(user).query(String.class).single();
    var previous =
        db.sql(
                "SELECT request_json,result_json FROM study_attempts WHERE user_id=? AND request_id=?")
            .params(user, attempt.requestId())
            .query(Stored.class)
            .optional();
    String requestJson = json.writeValueAsString(attempt);
    if (previous.isPresent()) {
      if (!json.readTree(previous.get().requestJson()).equals(json.readTree(requestJson)))
        throw ApiException.conflict();
      return json.readValue(previous.get().resultJson(), Result.class);
    }
    var subject = catalog.subject();
    if (!subject.id().equals(attempt.subjectId())) throw ApiException.missing();
    if (!subject.version().equals(attempt.version()))
      throw new ApiException(
          HttpStatus.CONFLICT,
          "STUDY_VERSION_CHANGED",
          "The exercises have been updated. Reload Exam prep before starting a new attempt.");
    List<StudyCatalog.Exercise> exercises =
        attempt.submissions().stream().map(s -> catalog.exercise(s.exerciseId())).toList();
    if (exercises.stream().map(StudyCatalog.Exercise::id).distinct().count() != exercises.size())
      throw invalid();
    if (attempt.mode().equals("PRACTICE") && exercises.size() != 1) throw invalid();
    if (attempt.mode().equals("MOCK")) {
      if (exercises.size() != 6
          || count(exercises, "Theory") != 3
          || count(exercises, "Resistance") != 1
          || count(exercises, "Equations") != 1
          || count(exercises, "Superposition") != 1) throw invalid();
    }
    var results = new ArrayList<ExerciseResult>();
    for (int i = 0; i < exercises.size(); i++) {
      var e = exercises.get(i);
      var answers = attempt.submissions().get(i).answers();
      Set<String> keys = new HashSet<>();
      e.fields().forEach(f -> keys.add(f.id()));
      if (!keys.containsAll(answers.keySet())) throw invalid();
      var fields =
          e.fields().stream()
              .map(
                  f ->
                      new FieldResult(
                          f.id(),
                          answers.getOrDefault(f.id(), ""),
                          StudyGrader.correct(f, answers.get(f.id()), e.constants()),
                          f.expected(),
                          f.explanation()))
              .toList();
      results.add(
          new ExerciseResult(
              e.id(),
              (int) fields.stream().filter(FieldResult::correct).count(),
              fields.size(),
              fields,
              e.steps(),
              e.publicView()));
    }
    var result =
        new Result(
            UUID.randomUUID().toString(),
            results.stream().mapToInt(ExerciseResult::earned).sum(),
            results.stream().mapToInt(ExerciseResult::possible).sum(),
            results);
    db.sql(
            "INSERT INTO study_attempts(id,user_id,request_id,subject_id,catalog_version,mode,request_json,result_json,earned,possible) VALUES (?,?,?,?,?,?,?,?,?,?)")
        .params(
            result.id(),
            user,
            attempt.requestId(),
            subject.id(),
            subject.version(),
            attempt.mode(),
            requestJson,
            json.writeValueAsString(result),
            result.earned(),
            result.possible())
        .update();
    audit.record("STUDY_ATTEMPT_SUBMITTED", "STUDY_ATTEMPT", result.id());
    return result;
  }

  private long count(List<StudyCatalog.Exercise> exercises, String topic) {
    return exercises.stream().filter(e -> e.topic().equals(topic)).count();
  }

  private ApiException invalid() {
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        "INVALID_STUDY_ATTEMPT",
        "Choose one practice exercise, or a mock exam with three theory questions and one each on resistance, equations and superposition.");
  }

  public Object progress() {
    String user = CurrentUser.id();
    var total =
        db.sql(
                "SELECT COUNT(*) attempts,COALESCE(SUM(earned),0) earned,COALESCE(SUM(possible),0) possible FROM study_attempts WHERE user_id=? AND subject_id=?")
            .params(user, catalog.subject().id())
            .query()
            .singleRow();
    var recent =
        db.sql(
                "SELECT id,mode,earned,possible,created_at FROM study_attempts WHERE user_id=? AND subject_id=? ORDER BY created_at DESC,id DESC LIMIT 20")
            .params(user, catalog.subject().id())
            .query()
            .listOfRows();
    return Map.of("total", total, "recent", recent);
  }

  public Result result(String id) {
    String stored =
        db.sql("SELECT result_json FROM study_attempts WHERE id=? AND user_id=?")
            .params(id, CurrentUser.id())
            .query(String.class)
            .optional()
            .orElseThrow(ApiException::missing);
    return json.readValue(stored, Result.class);
  }
}
