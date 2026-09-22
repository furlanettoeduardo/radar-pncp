package io.github.furlanettoeduardo.radar.domain.matching.rule;

import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Scores how much of what the profile is looking for actually appears in the object of the
 * procurement.
 *
 * <p>Strength is the fraction of the profile's keywords that were found, not a yes or no: a profile
 * listing four keywords and hitting one is a weaker signal than one hitting all four, and
 * collapsing that into a boolean throws the difference away.
 *
 * <p>Comparison folds case and accents, because a supplier typing {@code aquisicao} means the same
 * thing as the {@code AQUISIÇÃO} PNCP published. Matching is on whole words, so {@code TI} does not
 * hit inside {@code PARTIDA}. The known gap is inflection: {@code informatica} will not match
 * {@code informaticas}. Stemming is a larger decision than this rule should make on its own.
 */
public final class KeywordMatchRule implements ScoringRule {

  private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

  @Override
  public RuleOutcome evaluate(ScoringSubject subject, SearchProfile profile, Instant evaluatedAt) {
    List<String> keywords = profile.keywords();
    if (keywords.isEmpty()) {
      return new RuleOutcome.NotApplicable("the profile declares no keywords");
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
    Pattern wholeWord = Pattern.compile("\\b" + Pattern.quote(fold(keyword)) + "\\b");
    return wholeWord.matcher(foldedObject).find();
  }

  private static String fold(String text) {
    String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
    return DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
  }
}
