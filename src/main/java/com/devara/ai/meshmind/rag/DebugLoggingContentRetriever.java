package com.devara.ai.meshmind.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** Thin wrapper that delegates retrieval to any inner ContentRetriever and emits DEBUG logs of the query and each returned segment. */
@Slf4j
public class DebugLoggingContentRetriever implements ContentRetriever {

    private static final int PREVIEW_CHARS = 160;

    private final String label;
    private final ContentRetriever delegate;

    public DebugLoggingContentRetriever(String label, ContentRetriever delegate) {
        this.label = label;
        this.delegate = delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> results = delegate.retrieve(query);
        if (log.isDebugEnabled()) {
            log.debug("[{}] query=\"{}\" -> {} segments", label, query.text(), results.size());
            int rank = 0;
            for (Content c : results) {
                rank++;
                String text = c.textSegment() != null ? c.textSegment().text() : "<null>";
                log.debug("[{}]   #{} preview=\"{}\"", label, rank, preview(text));
            }
        }
        return results;
    }

    private static String preview(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "...";
    }
}
