package hu.bmepilots.portal.study;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded arithmetic only: never executes learner input. Equations compare coefficient vectors. */
public final class StudyGrader {
  private StudyGrader() {}

  private static final Pattern NUMBER =
      Pattern.compile(
          "^([+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)(?:[eE][+-]?\\d+)?)[ ]*([a-zA-ZΩµμ]*)$");

  public static boolean correct(
      StudyCatalog.Field field, String input, Map<String, Double> constants) {
    if (input == null || input.length() > 256 || input.isBlank()) return false;
    try {
      return switch (field.kind()) {
        case "choice" -> field.expected().equals(input.trim());
        case "numeric" -> {
          double expected = Double.parseDouble(field.expected());
          double actual = number(input, field.unit());
          yield Double.isFinite(actual)
              && Math.abs(actual - expected) <= Math.max(1e-6, Math.abs(expected) * .005);
        }
        case "equation" -> equivalent(input, field.expected(), constants);
        default -> false;
      };
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  static double number(String text, String unit) {
    var m = NUMBER.matcher(text.trim());
    if (!m.matches()) throw new IllegalArgumentException();
    String suffix = m.group(2).replace("Ω", "ohm").replace("μ", "u").replace("µ", "u");
    double scale = 1;
    if (!suffix.isEmpty() && !suffix.equals(unit)) {
      if (suffix.length() < 2 || !suffix.substring(1).equals(unit))
        throw new IllegalArgumentException();
      scale =
          switch (suffix.charAt(0)) {
            case 'p' -> 1e-12;
            case 'n' -> 1e-9;
            case 'u' -> 1e-6;
            case 'm' -> 1e-3;
            case 'k' -> 1e3;
            case 'M' -> 1e6;
            default -> throw new IllegalArgumentException();
          };
    }
    return Double.parseDouble(m.group(1).replace(',', '.')) * scale;
  }

  static boolean equivalent(String input, String expected, Map<String, Double> constants) {
    var b = equation(expected, constants, null);
    var a = equation(input, constants, b.keySet());
    String pivot =
        b.entrySet().stream()
            .max(Comparator.comparingDouble(e -> Math.abs(e.getValue())))
            .orElseThrow()
            .getKey();
    double ratio = a.getOrDefault(pivot, 0d) / b.get(pivot);
    if (!Double.isFinite(ratio) || Math.abs(ratio) < 1e-12) return false;
    Set<String> keys = new HashSet<>(a.keySet());
    keys.addAll(b.keySet());
    for (String key : keys) {
      double want = b.getOrDefault(key, 0d);
      if (Math.abs(a.getOrDefault(key, 0d) / ratio - want) > 1e-8 * Math.max(1, Math.abs(want)))
        return false;
    }
    return true;
  }

  private static Map<String, Double> equation(
      String text, Map<String, Double> constants, Set<String> allowed) {
    String[] sides = text.replace('−', '-').replace('×', '*').split("=", -1);
    if (sides.length != 2) throw new IllegalArgumentException();
    return add(
        new Parser(sides[0], constants, allowed).parse(),
        new Parser(sides[1], constants, allowed).parse(),
        -1);
  }

  private static Map<String, Double> add(
      Map<String, Double> a, Map<String, Double> b, double scale) {
    var out = new HashMap<>(a);
    b.forEach((k, v) -> out.merge(k, v * scale, Double::sum));
    out.values().removeIf(v -> v == 0);
    if (out.values().stream().anyMatch(v -> !Double.isFinite(v)))
      throw new IllegalArgumentException();
    return out;
  }

  private static Map<String, Double> scale(Map<String, Double> a, double scale) {
    if (!Double.isFinite(scale)) throw new IllegalArgumentException();
    return add(Map.of(), a, scale);
  }

  private static boolean constant(Map<String, Double> a) {
    return a.isEmpty() || a.keySet().equals(Set.of(""));
  }

  private static class Parser {
    final String s;
    final Map<String, Double> constants;
    final Set<String> allowed;
    int p;
    int depth;

    Parser(String s, Map<String, Double> constants, Set<String> allowed) {
      this.s = s.replaceAll("\\s+", "");
      this.constants = constants;
      this.allowed = allowed;
      if (s.length() > 256) throw new IllegalArgumentException();
    }

    Map<String, Double> parse() {
      var v = sum();
      if (p != s.length()) throw new IllegalArgumentException();
      return v;
    }

    boolean take(char c) {
      if (p < s.length() && s.charAt(p) == c) {
        p++;
        return true;
      }
      return false;
    }

    Map<String, Double> sum() {
      var v = product();
      while (p < s.length()) {
        if (take('+')) v = add(v, product(), 1);
        else if (take('-')) v = add(v, product(), -1);
        else break;
      }
      return v;
    }

    Map<String, Double> product() {
      var v = atom();
      while (p < s.length()) {
        if (take('*')) {
          var b = atom();
          if (constant(b)) v = scale(v, b.getOrDefault("", 0d));
          else if (constant(v)) v = scale(b, v.getOrDefault("", 0d));
          else throw new IllegalArgumentException();
        } else if (take('/')) {
          var b = atom();
          if (!constant(b)) throw new IllegalArgumentException();
          v = scale(v, 1 / b.getOrDefault("", 0d));
        } else break;
      }
      return v;
    }

    Map<String, Double> atom() {
      if (++depth > 32) throw new IllegalArgumentException();
      try {
        if (take('+')) return atom();
        if (take('-')) return scale(atom(), -1);
        if (take('(')) {
          var v = sum();
          if (!take(')')) throw new IllegalArgumentException();
          return v;
        }
        int start = p;
        if (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) {
          while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
          double n = Double.parseDouble(s.substring(start, p));
          if (!Double.isFinite(n)) throw new IllegalArgumentException();
          return Map.of("", n);
        }
        while (p < s.length() && Character.isLetterOrDigit(s.charAt(p))) p++;
        if (start == p) throw new IllegalArgumentException();
        String name = s.substring(start, p);
        if (constants.containsKey(name)) return Map.of("", constants.get(name));
        if (allowed != null && !allowed.contains(name)) throw new IllegalArgumentException();
        return Map.of(name, 1d);
      } finally {
        depth--;
      }
    }
  }
}
