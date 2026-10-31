package com.example.loan;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulated credit bureau with synthetic data only. Called by the REST Connector.
 * Applicant IDs starting with ERR return 503; IDs starting with SLOW exceed the 5s connector timeout.
 */
@RestController
public class MockCreditApi {

  private static final Map<String, Integer> SCORES =
      Map.of("A-100", 780, "A-200", 480, "A-300", 640, "A-600", 760);

  @GetMapping("/api/credit-score/{applicantId}")
  public ResponseEntity<Map<String, Object>> creditScore(@PathVariable String applicantId)
      throws InterruptedException {
    if (applicantId.startsWith("ERR")) {
      return ResponseEntity.status(503).body(Map.of("error", "Credit bureau unavailable"));
    }
    if (applicantId.startsWith("SLOW")) {
      Thread.sleep(10_000);
    }
    int score = SCORES.getOrDefault(applicantId, 650);
    return ResponseEntity.ok(
        Map.of("applicantId", applicantId, "creditScore", score, "reference", "CB-" + applicantId));
  }
}
