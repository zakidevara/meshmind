package com.devara.ai.meshmind.evaluation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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

/** Serializes RAG evaluation samples to a JSONL file for offline evaluation with RAGAS. */
@Slf4j
@Component
public class RagEvaluationLogger {

    private final ObjectMapper objectMapper;
    private final Path outputPath;

    /** A single RAGAS evaluation sample; groundTruth is optional and only required for context-precision/recall metrics. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvalSample(
        String question,
        List<String> contexts,
        String answer,
        @JsonProperty("ground_truth") String groundTruth
    ) {}

    /** Constructs the logger; output path defaults to {@code eval/input/eval_samples.jsonl} unless overridden by {@code eval.output-path}. */
    public RagEvaluationLogger(ObjectMapper objectMapper,
                                @Value("${eval.output-path:eval/input/eval_samples.jsonl}") String outputPath) {
        this.objectMapper = objectMapper;
        this.outputPath = Path.of(outputPath);
    }

    /** Logs a sample without ground truth (faithfulness + answer_relevancy only). */
    public void log(String question, String answer) {
        log(question, answer, null);
    }

    /** Logs a sample with an optional ground truth answer (enables context_precision + context_recall). */
    public void log(String question, String answer, String groundTruth) {
        List<Content> retrieved = LoggingContentRetriever.getLastRetrieved();
        if (retrieved == null || retrieved.isEmpty()) {
            log.warn("No retrieved contexts found for question: {}", question);
            return;
        }
        try {
            List<String> contexts = retrieved.stream()
                .map(c -> c.textSegment().text())
                .toList();
            String json = objectMapper.writeValueAsString(
                new EvalSample(question, contexts, answer, groundTruth));
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            Files.writeString(outputPath, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Logged eval sample to {}", outputPath);
        } catch (IOException e) {
            log.warn("Failed to write eval sample", e);
        } finally {
            LoggingContentRetriever.clear();
        }
    }
}
