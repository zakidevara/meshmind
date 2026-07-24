package com.devara.ai.meshmind.data;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class CsvTicketDataLoader implements CommandLineRunner {

  private final EmbeddingStore embeddingStore;
  private final EmbeddingModel embeddingModel;

  public CsvTicketDataLoader(EmbeddingStore embeddingStore,
                             EmbeddingModel embeddingModel) {
    this.embeddingStore = embeddingStore;
    this.embeddingModel = embeddingModel;
  }

  @Override
  public void run(String... args) throws Exception {
    ClassPathResource resource = new ClassPathResource("data/support_tickets_kaggle.csv");

    if (!resource.exists()) {
      System.out.println("No CSV found, skipping ingestion.");
      return;
    }

    List<Document> documents = new ArrayList<>();

    try (Reader reader = new InputStreamReader(resource.getInputStream())) {
      // Configure CSV parser to read the header row automatically
      Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
          .setHeader()
          .setSkipHeaderRecord(true)
          .build()
          .parse(reader);

      for (CSVRecord record : records) {
        // 1. Construct the main text payload for the LLM to read
        String documentContent = String.format(
            "Customer Issue: %s\nResolution: %s",
            record.get("message"),
            record.get("resolution_note")
        );

        // 2. Attach categorical data as Metadata
        Metadata metadata = new Metadata()
            .put("source", "support_csv")
            .put("subcategory", record.get("subcategory"))
            .put("priority", record.get("priority"))
            .put("status", record.get("status"))
            .put("sentiment", record.get("sentiment"))
            .put("product", record.get("product"))
            .put("customer_plan", record.get("customer_plan"))
            .put("region", record.get("region"))
            .put("assigned_agent", record.get("assigned_agent"));

        // Include numerical/boolean flags if they exist in the row
        if (record.isMapped("reopened")) {
          metadata.put("reopened", record.get("reopened"));
        }
        if (record.isMapped("escalated")) {
          metadata.put("escalated", record.get("escalated"));
        }

        documents.add(Document.from(documentContent, metadata));
      }
    }

    // 3. Ingest into the vector database
    EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
        .embeddingModel(embeddingModel)
        .embeddingStore(embeddingStore)
        .build();

    ingestor.ingest(documents);
    log.info("Ingested {} support tickets into the embedding store.", documents.size());
  }
}