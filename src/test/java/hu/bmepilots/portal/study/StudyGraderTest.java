package hu.bmepilots.portal.study;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StudyGraderTest {
  private StudyCatalog.Field numeric(String value, String unit) {
    return new StudyCatalog.Field("x", "Value", "numeric", unit, List.of(), value, "");
  }

  @Test
  void unitsRoundingSignsAndMalformedValues() {
    var f = numeric("0.02", "A");
    for (String s : List.of("20 mA", "0,02 A", "2e-2", "20000 uA", ".02009"))
      assertThat(StudyGrader.correct(f, s, Map.of())).as(s).isTrue();
    for (String s :
        List.of(
            "20 A", "20 mV", "NaN", "Infinity", "0.0202", "-20 mA", "20m", "1+1", "1e999 A", ""))
      assertThat(StudyGrader.correct(f, s, Map.of())).as(s).isFalse();
    assertThat(StudyGrader.correct(numeric("2200", "ohm"), "2.2 kΩ", Map.of())).isTrue();
    assertThat(StudyGrader.correct(numeric("0", "A"), "0.000002", Map.of())).isFalse();
  }

  @Test
  void equivalentEquationsAreAlgebraicNotSingleSolutionChecks() {
    var c = Map.of("R1", 100d);
    for (String s : List.of("U1 = R1*I1", "I1=U1/100", "-2*U1+200*I1=0", "0=U1-100*I1"))
      assertThat(StudyGrader.equivalent(s, "U1=100*I1", c)).as(s).isTrue();
    for (String s :
        List.of(
            "U1=101*I1",
            "U1=I1",
            "0=0",
            "1=1",
            "U1=100*I1+1",
            "U1=100*I2",
            "U1*I1=0",
            "U1/I1=100",
            "U1=0/0",
            "eval(1)=0",
            "((((((((((((((((((((((((((((((((((((1))))))))))))))))))))))))))))))))))))=0")) {
      var f = new StudyCatalog.Field("x", "Equation", "equation", "", List.of(), "U1=100*I1", "");
      assertThat(StudyGrader.correct(f, s, c)).as(s).isFalse();
    }
    assertThat(StudyGrader.equivalent("I1=I2+I3", "I1-I2-I3=0", Map.of())).isTrue();
  }

  @Test
  void bankCoverageTopologyAndIndependentGoldenValues() throws Exception {
    var catalog = new StudyCatalog(new ObjectMapper());
    var node = catalog.exercise("eq-node");
    assertThat(StudyGrader.correct(node.fields().get(1), "U1+U2=18", node.constants())).isTrue();
    assertThat(catalog.subject().exercises()).hasSize(38);
    for (var e : catalog.subject().exercises()) {
      for (var f : e.fields())
        assertThat(StudyGrader.correct(f, f.expected(), e.constants()))
            .as(e.id() + f.id())
            .isTrue();
      if (e.circuit() != null) {
        var nodes = e.circuit().nodes().stream().map(StudyCatalog.Node::id).toList();
        assertThat(nodes).doesNotHaveDuplicates();
        for (var p : e.circuit().parts()) {
          assertThat(nodes).contains(p.from(), p.to());
          if (p.kind().equals("resistor")) assertThat(p.value()).isPositive();
        }
      }
    }
    Map<String, Double> resistance =
        Map.of(
            "r-series",
            630d,
            "r-parallel",
            720d / 11,
            "r-nested",
            350d,
            "r-two-strings",
            200d,
            "r-ladder",
            300d,
            "r-triangle",
            275d,
            "r-port",
            225d,
            "r-bridge",
            180d);
    resistance.forEach(
        (id, value) ->
            assertThat(Double.parseDouble(catalog.exercise(id).fields().getFirst().expected()))
                .as(id)
                .isCloseTo(value, within(1e-8)));
    // Independent hand calculations: sources add signed current, power uses the total.
    assertField(catalog, "sp-aiding", "V1", .04);
    assertField(catalog, "sp-aiding", "J1", .01);
    assertField(catalog, "sp-aiding", "power", .5);
    assertField(catalog, "sp-reversal", "total", -.01);
    assertField(catalog, "sp-reversal", "power", .06);
    assertField(catalog, "sp-clamped", "J1", 0);
    assertField(catalog, "sp-clamped", "V1", .03);
    assertField(catalog, "sp-two-current", "total", .03);
    assertField(catalog, "ac-capacitor", "X", 1000 / Math.PI);
    assertField(catalog, "ac-inductor", "X", 64 * Math.PI);
    String publicJson = new ObjectMapper().writeValueAsString(catalog.publicView());
    assertThat(publicJson).doesNotContain("\"expected\"", "\"steps\"", "\"explanation\"");
  }

  private void assertField(StudyCatalog catalog, String id, String field, double expected) {
    var actual =
        catalog.exercise(id).fields().stream()
            .filter(f -> f.id().equals(field))
            .findFirst()
            .orElseThrow();
    assertThat(Double.parseDouble(actual.expected()))
        .as(id + "/" + field)
        .isCloseTo(expected, within(1e-8));
  }
}
