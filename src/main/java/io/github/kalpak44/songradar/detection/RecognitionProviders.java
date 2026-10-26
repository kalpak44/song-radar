package io.github.kalpak44.songradar.detection;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * The recognition provider in use, resolved per call from the settings page rather than fixed at
 * startup. Everything else talks to this and never to a particular provider, so switching one takes
 * effect on the next recording instead of a restart.
 */
public class RecognitionProviders implements RecognitionClient {

  private final RecognitionSettingsRepository settings;
  private final Map<RecognitionProvider, RecognitionClient> clients;

  public RecognitionProviders(
      RecognitionSettingsRepository settings, Map<RecognitionProvider, RecognitionClient> clients) {
    this.settings = settings;
    this.clients = Map.copyOf(clients);
  }

  /** The provider currently selected, whether or not it has what it needs to run. */
  public RecognitionProvider active() {
    return settings.findProvider();
  }

  public void select(RecognitionProvider provider) {
    settings.saveProvider(Objects.requireNonNull(provider));
  }

  /**
   * Whether a provider could run if it were selected — AudD needs its token, Shazam needs nothing.
   */
  public boolean isConfigured(RecognitionProvider provider) {
    return client(provider).isConfigured();
  }

  @Override
  public String name() {
    return client(active()).name();
  }

  @Override
  public boolean isConfigured() {
    return client(active()).isConfigured();
  }

  @Override
  public String unavailableReason() {
    return client(active()).unavailableReason();
  }

  @Override
  public Map<String, String> recognize(Path file) {
    return client(active()).recognize(file);
  }

  private RecognitionClient client(RecognitionProvider provider) {
    RecognitionClient client = clients.get(provider);
    if (client == null) {
      throw new IllegalStateException("No client for recognition provider " + provider);
    }
    return client;
  }
}
