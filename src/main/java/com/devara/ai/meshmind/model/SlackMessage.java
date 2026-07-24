package com.devara.ai.meshmind.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SlackMessage(
    String type,
    String user,
    String text,
    String ts,
    @JsonProperty("thread_ts") String threadTs
) {}

