package com.example.loan;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ValidateApplicantWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ValidateApplicantWorker.class);

  /**
   * Business-invalid input -> valid=false (process routes to "Invalid application").
   * Missing monthlyIncome -> exception: the job fails, retries run out and Operate shows an
   * incident. That is the intentional failure for the recovery test.
   */
  @JobWorker(type = "validate-applicant")
  public Map<String, Object> validate(
      @Variable String applicantId,
      @Variable String applicantName,
      @Variable Integer age,
      @Variable Double loanAmount,
      @Variable Double monthlyIncome,
      @Variable Integer loanTermMonths) {

    LOG.info("Validating applicant {}", applicantId);
    if (monthlyIncome == null) {
      throw new IllegalStateException(
          "monthlyIncome is missing for applicant " + applicantId + " - add it in Operate and retry");
    }

    List<String> errors = new ArrayList<>();
    if (applicantId == null || applicantId.isBlank()) errors.add("applicantId is required");
    if (applicantName == null || applicantName.isBlank()) errors.add("applicantName is required");
    if (age == null || age < 18 || age > 75) errors.add("age must be between 18 and 75");
    if (loanAmount == null || loanAmount <= 0 || loanAmount > 500_000)
      errors.add("loanAmount must be between 1 and 500000");
    if (loanTermMonths == null || loanTermMonths < 6 || loanTermMonths > 360)
      errors.add("loanTermMonths must be between 6 and 360");
    if (monthlyIncome <= 0) errors.add("monthlyIncome must be positive");

    if (!errors.isEmpty()) {
      return Map.of("valid", false, "validationErrors", errors);
    }

    // ponytail: interest-free installment, good enough for the educational DMN rules
    double installment = round(loanAmount / loanTermMonths);
    return Map.of(
        "valid", true,
        "validationErrors", errors,
        "monthlyInstallment", installment,
        "paymentToIncomeRatio", round(installment / monthlyIncome));
  }

  private static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }
}
