/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FeedbackMetricTest {

  @Test
  void resultOfCarriesScoreAndFeedback() {
    var r = FeedbackMetric.Result.of(0.75, "close, but missed the citation");
    assertEquals(0.75, r.score());
    assertEquals("close, but missed the citation", r.feedback());
  }

  @Test
  void scoreOnlyHasEmptyFeedback() {
    var r = FeedbackMetric.Result.scoreOnly(1.0);
    assertEquals(1.0, r.score());
    assertEquals("", r.feedback());
  }

  @Test
  void blankFeedbackDegradesToEmptyString() {
    var r = new FeedbackMetric.Result(0.5, null);
    assertEquals("", r.feedback());
    var blank = new FeedbackMetric.Result(0.5, "   ");
    assertEquals("", blank.feedback());
  }

  @Test
  void asScalarReturnsSameScoreAndDropsFeedback() {
    FeedbackMetric<String, String> fm =
        (e, a, t) -> FeedbackMetric.Result.of(e.equals(a) ? 1.0 : 0.0, "exact-match");
    var scalar = fm.asScalar();
    assertEquals(1.0, scalar.score("hi", "hi", null));
    assertEquals(0.0, scalar.score("hi", "bye", null));
  }
}
