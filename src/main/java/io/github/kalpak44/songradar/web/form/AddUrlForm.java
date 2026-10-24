package io.github.kalpak44.songradar.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The "Add playlist URL" form. */
public class AddUrlForm {

  @NotBlank(message = "Enter a playlist URL.")
  @Size(max = 2000, message = "That URL is too long.")
  private String url;

  @Size(max = 200, message = "Use a shorter name.")
  private String name;

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
