package com.devara.ai.meshmind.data;

import com.devara.ai.meshmind.SlackThreadSummarizer;
import com.devara.ai.meshmind.model.SlackExport;
import com.devara.ai.meshmind.model.SlackMessage;
import com.devara.ai.meshmind.rag.BM25ContentRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@Slf4j
public class SlackDataLoader implements CommandLineRunner {
  private final EmbeddingStore<TextSegment> embeddingStore;
  private final EmbeddingModel embeddingModel;
  private final ObjectMapper objectMapper;
  private final SlackThreadSummarizer summarizer;
  private final SummaryCache summaryCache;
  private final BM25ContentRetriever bm25ContentRetriever;
  private final boolean summarizeEnabled;
  private final Path summaryCachePath;

  public SlackDataLoader(EmbeddingStore<TextSegment> embeddingStore,
                         EmbeddingModel embeddingModel,
                         ObjectMapper objectMapper,
                         SlackThreadSummarizer summarizer,
                         SummaryCache summaryCache,
                         BM25ContentRetriever bm25ContentRetriever,
                         @Value("${app.ingest.summarize:true}") boolean summarizeEnabled,
                         @Value("${app.ingest.summary-cache-path:data/slack_summaries.json}") String summaryCachePath) {
    this.embeddingStore = embeddingStore;
    this.embeddingModel = embeddingModel;
    this.objectMapper = objectMapper;
    this.summarizer = summarizer;
    this.summaryCache = summaryCache;
    this.bm25ContentRetriever = bm25ContentRetriever;
    this.summarizeEnabled = summarizeEnabled;
    this.summaryCachePath = Path.of(summaryCachePath);
  }

  @Override
  public void run(String... args) throws Exception {
    ClassPathResource resource = new ClassPathResource("data/slack_oncall_export.json");
    SlackExport export = objectMapper.readValue(resource.getInputStream(), SlackExport.class);

    Map<String, List<SlackMessage>> threadsByTimestamp = export.messages().stream()
        .filter(message -> message.threadTs() != null)
        .collect(Collectors.groupingBy(SlackMessage::threadTs));

    log.info("Loaded {} Slack threads. Summarization enabled: {}", threadsByTimestamp.size(), summarizeEnabled);

    // 1. Produce summaries — from cache if available, otherwise via LLM.
    Map<String, String> summariesByTs = resolveSummaries(threadsByTimestamp);

    // 2. Build the documents (each thread = 1 doc) so both stores index the same content.
    List<Document> documents = new ArrayList<>();
    for (Map.Entry<String, List<SlackMessage>> entry : threadsByTimestamp.entrySet()) {
      String threadTs = entry.getKey();
      List<SlackMessage> threadMessages = entry.getValue();
      threadMessages.sort(Comparator.comparing(SlackMessage::ts));
      String rawThread = threadMessages.stream()
          .map(m -> m.user() + ": " + m.text())
          .collect(Collectors.joining("\n"));
      String content = summariesByTs.get(threadTs);
      Metadata metadata = Metadata.from("source", "slack_oncall")
          .put("thread_ts", threadTs)
          .put("raw_thread", rawThread);
      documents.add(Document.from(content, metadata));
    }

    // 3. Always (re)build the BM25 index — it is in-memory and empty on every restart.
    List<TextSegment> segments = documents.stream()
        .map(doc -> TextSegment.from(doc.text(), doc.metadata()))
        .toList();
    bm25ContentRetriever.index(segments);

    // 4. Populate Milvus only if empty (persistent across restarts).
    if (isAlreadyPopulated()) {
      log.info("Milvus already populated; skipping vector ingestion. Wipe volumes/collection to re-ingest.");
    } else {
      EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
          .embeddingModel(embeddingModel)
          .embeddingStore(embeddingStore)
          .build();
      ingestor.ingest(documents);
      log.info("Ingested {} Slack threads into Milvus.", documents.size());
    }

    if (log.isDebugEnabled()) {
      documents.forEach(doc -> log.debug("Ingested doc:\n{}\n---", doc.text()));
    }
  }

  private Map<String, String> resolveSummaries(Map<String, List<SlackMessage>> threadsByTimestamp) {
    if (!summarizeEnabled) {
      // Fall back to raw chat when summarization is disabled — same content in both stores.
      Map<String, String> raw = new LinkedHashMap<>();
      for (var entry : threadsByTimestamp.entrySet()) {
        List<SlackMessage> msgs = new ArrayList<>(entry.getValue());
        msgs.sort(Comparator.comparing(SlackMessage::ts));
        raw.put(entry.getKey(), msgs.stream().map(m -> m.user() + ": " + m.text()).collect(Collectors.joining("\n")));
      }
      return raw;
    }

    Optional<Map<String, String>> cached = summaryCache.load(summaryCachePath);
    if (cached.isPresent() && cached.get().keySet().containsAll(threadsByTimestamp.keySet())) {
      return cached.get();
    }

    Map<String, String> summaries = new LinkedHashMap<>();
    int i = 0, total = threadsByTimestamp.size();
    for (var entry : threadsByTimestamp.entrySet()) {
      i++;
      List<SlackMessage> msgs = new ArrayList<>(entry.getValue());
      msgs.sort(Comparator.comparing(SlackMessage::ts));
      String rawThread = msgs.stream().map(m -> m.user() + ": " + m.text()).collect(Collectors.joining("\n"));
      log.info("Summarizing thread {}/{} ({})", i, total, entry.getKey());
      summaries.put(entry.getKey(), summarizer.summarize(rawThread));
    }
    summaryCache.save(summaryCachePath, summaries);
    return summaries;
  }

  private boolean isAlreadyPopulated() {
    try {
      Embedding probe = embeddingModel.embed("probe").content();
      var result = embeddingStore.search(EmbeddingSearchRequest.builder()
          .queryEmbedding(probe)
          .maxResults(1)
          .build());
      return !result.matches().isEmpty();
    } catch (Exception e) {
      log.warn("Could not probe embedding store; proceeding with ingestion. Reason: {}", e.getMessage());
      return false;
    }
  }
}
