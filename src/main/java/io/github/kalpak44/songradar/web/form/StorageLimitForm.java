package io.github.kalpak44.songradar.web.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Form value is expressed in decimal gigabytes, matching the number shown in the UI. */
public class StorageLimitForm {

  @NotNull(message = "Enter a storage limit.")
  @DecimalMin(value = "0.1", message = "The storage limit must be at least 0.1 GB.")
  @Digits(integer = 9, fraction = 1, message = "Use at most one decimal place.")
  private BigDecimal limitGb;

  public BigDecimal getLimitGb() {
    return limitGb;
  }

  public void setLimitGb(BigDecimal limitGb) {
    this.limitGb = limitGb;
  }
}
