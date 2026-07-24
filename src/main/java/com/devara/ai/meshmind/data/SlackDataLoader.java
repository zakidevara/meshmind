package com.devara.ai.meshmind.data;

import com.devara.ai.meshmind.model.SlackExport;
import com.devara.ai.meshmind.model.SlackMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.extern.slf4j.Slf4j;
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
  private final EmbeddingStore embeddingStore;
  private final EmbeddingModel embeddingModel;
  private final ObjectMapper objectMapper;

  public SlackDataLoader(EmbeddingStore embeddingStore,
                         EmbeddingModel embeddingModel,
                         ObjectMapper objectMapper) {
    this.embeddingStore = embeddingStore;
    this.embeddingModel = embeddingModel;
    this.objectMapper = objectMapper;
  }

  @Override
  public void run(String... args) throws Exception {
    // read json file from resources folder
    ClassPathResource resource = new ClassPathResource("data/slack_oncall_export.json");

    SlackExport export = objectMapper.readValue(resource.getInputStream(), SlackExport.class);

    // group messages to threads by thread_ts
    Map<String, List<SlackMessage>> threadsByTimestamp = export.messages().stream()
        .filter(message -> message.threadTs() != null)
        .collect(Collectors.groupingBy(SlackMessage::threadTs));

    // convert each thread to single document
    List<Document> documents = new ArrayList<>();
    for (Map.Entry<String, List<SlackMessage>> entry : threadsByTimestamp.entrySet()) {

      List<SlackMessage> threadMessages = entry.getValue();

      threadMessages.sort(Comparator.comparing(SlackMessage::ts));

      // concat conversations
      String threadContent = threadMessages.stream()
          .map(m -> m.user() + ": " + m.text())
          .collect(Collectors.joining("\n"));

      Metadata metadata = Metadata.from("source", "slack_oncall")
          .put("thread_ts", entry.getKey());

      documents.add(Document.from(threadContent, metadata));
    }

    // ingest to vector store
    EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
        .embeddingModel(embeddingModel)
        .embeddingStore(embeddingStore)
        .build();

    ingestor.ingest(documents);

    log.info("Ingested {} Slack threads into the embedding store.", documents.size());
  }
}
