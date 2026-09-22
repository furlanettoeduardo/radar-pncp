package io.github.furlanettoeduardo.radar.domain.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.matching.MatchId;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfileId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeedbackTest {

  private static final Instant GIVEN_AT = Instant.parse("2026-09-22T12:00:00Z");
  private static final MatchId MATCH =
      MatchId.of(
          new PncpControlNumber("44935278000126-1-000343/2025"),
          new SearchProfileId(UUID.randomUUID()));

  @Test
  @DisplayName("records a verdict against the match it is about")
  void recordsAVerdict() {
    Feedback feedback = Feedback.relevant(MATCH, GIVEN_AT);

    assertThat(feedback.match()).isEqualTo(MATCH);
    assertThat(feedback.verdict()).isEqualTo(Verdict.RELEVANT);
    assertThat(feedback.givenAt()).isEqualTo(GIVEN_AT);
  }

  @Test
  @DisplayName("a rejection is as much a verdict as an acceptance")
  void recordsARejection() {
    assertThat(Feedback.notRelevant(MATCH, GIVEN_AT).verdict()).isEqualTo(Verdict.NOT_RELEVANT);
  }
}
