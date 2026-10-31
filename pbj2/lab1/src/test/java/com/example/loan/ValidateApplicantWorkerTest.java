package com.example.loan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidateApplicantWorkerTest {

  private final ValidateApplicantWorker worker = new ValidateApplicantWorker();

  @Test
  void validApplicantGetsRatio() {
    Map<String, Object> out = worker.validate("A-100", "Test User", 30, 20000.0, 6000.0, 36);
    assertEquals(true, out.get("valid"));
    assertEquals(555.56, out.get("monthlyInstallment"));
    assertEquals(0.09, out.get("paymentToIncomeRatio"));
  }

  @Test
  void underageApplicantIsInvalid() {
    Map<String, Object> out = worker.validate("A-700", "Test Minor", 16, 5000.0, 2000.0, 12);
    assertEquals(false, out.get("valid"));
  }

  @Test
  void missingIncomeFailsTheJob() {
    assertThrows(IllegalStateException.class,
        () -> worker.validate("A-600", "Test User", 40, 10000.0, null, 24));
  }
}
