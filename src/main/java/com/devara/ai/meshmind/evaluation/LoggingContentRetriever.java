package com.devara.ai.meshmind.evaluation;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;

/** Wraps a {@link ContentRetriever} and captures retrieved content in a ThreadLocal for post-hoc evaluation logging. */
public class LoggingContentRetriever implements ContentRetriever {

    private static final InheritableThreadLocal<List<Content>> LAST_RETRIEVED = new InheritableThreadLocal<>(); // each thread have their own copy, preventing race condition & data contamination

    private final ContentRetriever delegate;

    /** Constructs a logging wrapper around the given retriever. */
    public LoggingContentRetriever(ContentRetriever delegate) {
        this.delegate = delegate;
    }

    /** Delegates retrieval to the wrapped retriever and stores the results in a ThreadLocal. */
    @Override
    public List<Content> retrieve(Query query) {
        List<Content> contents = delegate.retrieve(query);
        LAST_RETRIEVED.set(contents);
        return contents;
    }

    /** Returns the content retrieved during the most recent {@link #retrieve} call on this thread. */
    public static List<Content> getLastRetrieved() {
        return LAST_RETRIEVED.get();
    }

    /** Allows external components (e.g. LoggingContentAggregator) to publish the fused content into the same ThreadLocal. */
    public static void setLastRetrieved(List<Content> contents) {
        LAST_RETRIEVED.set(contents);
    }

    /** Removes the stored content from the current thread, preventing memory leaks in thread pools. */
    public static void clear() {
        LAST_RETRIEVED.remove();
    }
}