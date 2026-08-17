package com.devara.ai.meshmind.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Persists per-thread LLM summaries to a JSON file so subsequent app restarts don't repay the summarization cost. */
@Slf4j
@Component
public class SummaryCache {

    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public SummaryCache(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Returns the cached thread_ts → summary map if the file exists and parses cleanly, otherwise empty. */
    public Optional<Map<String, String>> load(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            Map<String, String> map = objectMapper.readValue(path.toFile(), MAP_TYPE);
            log.info("Loaded {} summaries from cache at {}", map.size(), path);
            return Optional.of(map);
        } catch (IOException e) {
            log.warn("Failed to read summary cache at {}; will regenerate. Reason: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /** Writes the map to disk, creating parent directories as needed. */
    public void save(Path path, Map<String, String> summaries) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), summaries);
            log.info("Wrote {} summaries to cache at {}", summaries.size(), path);
        } catch (IOException e) {
            log.warn("Failed to write summary cache at {}. Reason: {}", path, e.getMessage());
        }
    }
}
