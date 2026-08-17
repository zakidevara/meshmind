package com.devara.ai.meshmind.rag;

import com.devara.ai.meshmind.evaluation.LoggingContentRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Wraps a delegate ContentAggregator (typically DefaultContentAggregator which does Reciprocal Rank Fusion),
 * captures the fused output in the shared eval ThreadLocal, and emits DEBUG logs showing per-retriever
 * inputs and the final fused output — the exact context handed to the LLM.
 */
@Slf4j
public class LoggingContentAggregator implements ContentAggregator {

    private static final int PREVIEW_CHARS = 160;

    private final ContentAggregator delegate;

    public LoggingContentAggregator(ContentAggregator delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        if (log.isDebugEnabled()) {
            for (Map.Entry<Query, Collection<List<Content>>> queryEntry : queryToContents.entrySet()) {
                Collection<List<Content>> perRetriever = queryEntry.getValue();
                int i = 0;
                for (List<Content> list : perRetriever) {
                    i++;
                    log.debug("[fusion] input retriever#{} for query=\"{}\" -> {} segments",
                        i, queryEntry.getKey().text(), list.size());
                }
            }
        }

        List<Content> fused = delegate.aggregate(queryToContents);

        // Capture into the shared ThreadLocal so RagEvaluationLogger picks up the fused (post-RRF) context.
        LoggingContentRetriever.setLastRetrieved(new ArrayList<>(fused));

        if (log.isDebugEnabled()) {
            log.debug("[fusion] output: {} segments", fused.size());
            int rank = 0;
            for (Content c : fused) {
                rank++;
                String text = c.textSegment() != null ? c.textSegment().text() : "<null>";
                log.debug("[fusion]   #{} preview=\"{}\"", rank, preview(text));
            }
        }
        return fused;
    }

    private static String preview(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "...";
    }
}
