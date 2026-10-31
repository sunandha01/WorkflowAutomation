package com.example.order;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Simulated inventory system with synthetic products. Can be switched "down" for the incident test. */
@Component
public class InventoryService {

  public record Product(String name, double price, int stock) {}

  private static final Map<String, Product> CATALOG = Map.of(
      "P-100", new Product("Wireless Mouse", 25.0, 50),
      "P-200", new Product("Mechanical Keyboard", 80.0, 3),
      "P-300", new Product("Laptop", 1200.0, 10));

  private final AtomicBoolean down = new AtomicBoolean(false);

  public Product find(String productId) {
    if (down.get()) {
      throw new IllegalStateException("Inventory service unavailable - restore it and retry");
    }
    return CATALOG.get(productId);
  }

  public void setDown(boolean value) {
    down.set(value);
  }

  public boolean isDown() {
    return down.get();
  }
}
