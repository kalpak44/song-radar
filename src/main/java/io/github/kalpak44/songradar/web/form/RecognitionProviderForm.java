package io.github.kalpak44.songradar.web.form;

import jakarta.validation.constraints.NotBlank;

public class RecognitionProviderForm {

  @NotBlank(message = "Choose a recognition provider.")
  private String provider;

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }
}
