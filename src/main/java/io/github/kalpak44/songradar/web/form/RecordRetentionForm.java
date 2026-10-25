package io.github.kalpak44.songradar.web.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class RecordRetentionForm {

  @Min(value = 1, message = "Keep at least one recording per listener.")
  @Max(value = 1000, message = "Keep no more than 1,000 recordings per listener.")
  private int recordsPerListener = 20;

  public int getRecordsPerListener() {
    return recordsPerListener;
  }

  public void setRecordsPerListener(int recordsPerListener) {
    this.recordsPerListener = recordsPerListener;
  }
}
