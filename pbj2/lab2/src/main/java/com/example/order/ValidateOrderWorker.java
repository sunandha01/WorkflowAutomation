package com.example.order;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ValidateOrderWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ValidateOrderWorker.class);
  private static final double EXPRESS_FEE = 15.0;

  private final InventoryService inventory;

  public ValidateOrderWorker(InventoryService inventory) {
    this.inventory = inventory;
  }

  /**
   * Validates the order, checks stock and calculates the total.
   * Business problems -> orderValid=false / inStock=false (process routes on them).
   * Inventory outage -> exception: retries run out and Operate shows an incident (recovery test).
   */
  @JobWorker(type = "validate-order")
  public Map<String, Object> validate(
      @Variable String customerRef,
      @Variable String customerCategory,
      @Variable String productId,
      @Variable Integer quantity,
      @Variable String deliveryPreference,
      @Variable String paymentToken) {

    LOG.info("Validating order {} x{} for {}", productId, quantity, customerRef);
    List<String> errors = new ArrayList<>();
    if (customerRef == null || customerRef.isBlank()) errors.add("customerRef is required");
    if (!List.of("REGULAR", "GOLD").contains(customerCategory)) errors.add("customerCategory must be REGULAR or GOLD");
    if (quantity == null || quantity < 1 || quantity > 100) errors.add("quantity must be between 1 and 100");
    if (!List.of("STANDARD", "EXPRESS").contains(deliveryPreference)) errors.add("deliveryPreference must be STANDARD or EXPRESS");
    if (paymentToken == null || paymentToken.isBlank()) errors.add("paymentToken is required");

    InventoryService.Product product = productId == null ? null : inventory.find(productId);
    if (product == null) errors.add("unknown productId " + productId);

    if (!errors.isEmpty()) {
      return Map.of("orderValid", false, "inStock", false, "validationErrors", errors);
    }

    double shipping = "EXPRESS".equals(deliveryPreference) ? EXPRESS_FEE : 0.0;
    double total = Math.round((product.price() * quantity + shipping) * 100) / 100.0;
    return Map.of(
        "orderValid", true,
        "validationErrors", errors,
        "productName", product.name(),
        "unitPrice", product.price(),
        "availableStock", product.stock(),
        "inStock", product.stock() >= quantity,
        "orderTotal", total);
  }
}
