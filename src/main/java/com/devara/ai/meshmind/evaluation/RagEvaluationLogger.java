package com.devara.ai.meshmind.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Serializes RAG evaluation samples (question, retrieved contexts, answer) to a JSONL file for offline evaluation with RAGAS. */
@Slf4j
@Component
public class RagEvaluationLogger {

    private final ObjectMapper objectMapper;
    private final Path outputPath;

    /** Holds a single evaluation sample containing the question, retrieved contexts, and generated answer. */
    public record EvalSample(String question, List<String> contexts, String answer) {}

    /** Constructs the logger; output path defaults to {@code eval_samples.jsonl} unless overridden by {@code eval.output-path}. */
    public RagEvaluationLogger(ObjectMapper objectMapper,
                                @Value("${eval.output-path:eval_samples.jsonl}") String outputPath) {
        this.objectMapper = objectMapper;
        this.outputPath = Path.of(outputPath);
    }

    /** Reads retrieved contexts from the current thread, then appends a JSONL record of the question, contexts, and answer to the output file. */
    public void log(String question, String answer) {
        List<Content> retrieved = LoggingContentRetriever.getLastRetrieved();
        if (retrieved == null || retrieved.isEmpty()) {
            log.warn("No retrieved contexts found for question: {}", question);
            return;
        }
        try {
            List<String> contexts = retrieved.stream()
                .map(c -> c.textSegment().text())
                .toList();
            String json = objectMapper.writeValueAsString(new EvalSample(question, contexts, answer));
            Files.writeString(outputPath, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Logged eval sample to {}", outputPath);
        } catch (IOException e) {
            log.warn("Failed to write eval sample", e);
        } finally {
            LoggingContentRetriever.clear();
        }
    }
}
