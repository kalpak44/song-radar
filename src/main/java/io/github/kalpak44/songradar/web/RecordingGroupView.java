package io.github.kalpak44.songradar.web;

import java.util.List;

public record RecordingGroupView(
    long playlistId,
    String playlistName,
    long listenerId,
    String listenerName,
    String channelName,
    List<RecordingView> recordings) {}
