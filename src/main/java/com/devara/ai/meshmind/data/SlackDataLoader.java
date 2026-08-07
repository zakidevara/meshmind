package com.devara.ai.meshmind.data;

import com.devara.ai.meshmind.SlackThreadSummarizer;
import com.devara.ai.meshmind.model.SlackExport;
import com.devara.ai.meshmind.model.SlackMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class SlackDataLoader implements CommandLineRunner {
  private final EmbeddingStore<TextSegment> embeddingStore;
  private final EmbeddingModel embeddingModel;
  private final ObjectMapper objectMapper;
  private final SlackThreadSummarizer summarizer;
  private final boolean summarizeEnabled;

  public SlackDataLoader(EmbeddingStore<TextSegment> embeddingStore,
                         EmbeddingModel embeddingModel,
                         ObjectMapper objectMapper,
                         SlackThreadSummarizer summarizer,
                         @Value("${app.ingest.summarize:true}") boolean summarizeEnabled) {
    this.embeddingStore = embeddingStore;
    this.embeddingModel = embeddingModel;
    this.objectMapper = objectMapper;
    this.summarizer = summarizer;
    this.summarizeEnabled = summarizeEnabled;
  }

  @Override
  public void run(String... args) throws Exception {
    ClassPathResource resource = new ClassPathResource("data/slack_oncall_export.json");
    SlackExport export = objectMapper.readValue(resource.getInputStream(), SlackExport.class);

    Map<String, List<SlackMessage>> threadsByTimestamp = export.messages().stream()
        .filter(message -> message.threadTs() != null)
        .collect(Collectors.groupingBy(SlackMessage::threadTs));

    log.info("Loaded {} Slack threads. Summarization enabled: {}", threadsByTimestamp.size(), summarizeEnabled);

    List<Document> documents = new ArrayList<>();
    int index = 0;
    for (Map.Entry<String, List<SlackMessage>> entry : threadsByTimestamp.entrySet()) {
      index++;
      List<SlackMessage> threadMessages = entry.getValue();
      threadMessages.sort(Comparator.comparing(SlackMessage::ts));

      String rawThread = threadMessages.stream()
          .map(m -> m.user() + ": " + m.text())
          .collect(Collectors.joining("\n"));

      String content;
      if (summarizeEnabled) {
        log.info("Summarizing thread {}/{} ({})", index, threadsByTimestamp.size(), entry.getKey());
        content = summarizer.summarize(rawThread);
      } else {
        content = rawThread;
      }

      Metadata metadata = Metadata.from("source", "slack_oncall")
          .put("thread_ts", entry.getKey())
          .put("raw_thread", rawThread);

      documents.add(Document.from(content, metadata));
    }

    EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
        .embeddingModel(embeddingModel)
        .embeddingStore(embeddingStore)
        .build();

    ingestor.ingest(documents);

    log.info("Ingested {} Slack threads into the embedding store.", documents.size());
    if (log.isDebugEnabled()) {
      documents.forEach(doc -> log.debug("Ingested doc:\n{}\n---", doc.text()));
    }
  }
}
