package io.github.furlanettoeduardo.radar.domain.matching.rule;

import io.github.furlanettoeduardo.radar.domain.matching.RuleId;
import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scores how much of what the profile is looking for actually appears in the object of the
 * procurement.
 *
 * <p>Strength is the fraction of the profile's keywords that were found, not a yes or no: a profile
 * listing four keywords and hitting one is a weaker signal than one hitting all four, and
 * collapsing that into a boolean throws the difference away.
 *
 * <p>Comparison folds three things, because a supplier should not have to guess how a buyer typed
 * the notice:
 *
 * <ul>
 *   <li>case, so {@code AQUISIÇÃO} and {@code aquisição} are the same word;
 *   <li>accents, so a supplier typing {@code aquisicao} means the {@code AQUISIÇÃO} PNCP published;
 *   <li>regular plurals, in both directions, so {@code computador} finds {@code computadores} and
 *       {@code brinquedos} finds {@code brinquedo}.
 * </ul>
 *
 * <p>Matching stays anchored on whole words. Plural folding widens what counts as the same word, it
 * does not turn the rule into a prefix match: {@code cabo} still does not hit inside {@code
 * cabocla}.
 *
 * <p>The known gap is irregular plurals. Portuguese forms like {@code material} to {@code
 * materiais}, or {@code ão} to {@code ões}, are not folded, and a test records that rather than
 * leaving it to be discovered. Closing it properly means a stemmer, which is a larger decision than
 * this rule should make on its own.
 */
public final class KeywordMatchRule implements ScoringRule {

  private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Matches nothing, for a keyword that folds away to nothing at all. */
  private static final Pattern NEVER = Pattern.compile("(?!)");

  @Override
  public RuleId id() {
    return RuleId.KEYWORD;
  }

  @Override
  public RuleOutcome evaluate(ScoringSubject subject, SearchProfile profile, Instant evaluatedAt) {
    List<String> keywords = profile.keywords();
    if (keywords.isEmpty()) {
      return new RuleOutcome.Unavailable("the profile declares no keywords");
    }

    String object = fold(subject.procurement().objectDescription());
    List<String> hits = keywords.stream().filter(keyword -> containsWord(object, keyword)).toList();

    if (hits.isEmpty()) {
      return new RuleOutcome.Silent(
          "none of the %d profile keywords appear in the object description"
              .formatted(keywords.size()));
    }
    return new RuleOutcome.Contributed(
        (double) hits.size() / keywords.size(),
        "matched %d of %d keywords: %s"
            .formatted(hits.size(), keywords.size(), String.join(", ", hits)));
  }

  private static boolean containsWord(String foldedObject, String keyword) {
    return pluralTolerantPattern(fold(keyword)).matcher(foldedObject).find();
  }

  /**
   * Builds a whole word pattern that accepts the singular and the regular plural of every token in
   * the keyword, having first reduced the keyword itself to its singular so that a supplier may
   * type either form.
   */
  private static Pattern pluralTolerantPattern(String foldedKeyword) {
    String body =
        WHITESPACE
            .splitAsStream(foldedKeyword.trim())
            .filter(token -> !token.isEmpty())
            .map(token -> Pattern.quote(singularize(token)) + "(?:es|s)?")
            .collect(Collectors.joining("\\s+"));
    return body.isEmpty() ? NEVER : Pattern.compile("\\b" + body + "\\b");
  }

  /**
   * Strips a regular Portuguese plural ending. The length guards keep short words that merely end
   * in {@code s} intact: {@code gas} must not become {@code ga}, and {@code mes} must not become
   * {@code me}.
   */
  private static String singularize(String token) {
    if (token.length() > 4 && token.endsWith("es")) {
      return token.substring(0, token.length() - 2);
    }
    if (token.length() > 3 && token.endsWith("s")) {
      return token.substring(0, token.length() - 1);
    }
    return token;
  }

  private static String fold(String text) {
    String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
    return DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
  }
}
