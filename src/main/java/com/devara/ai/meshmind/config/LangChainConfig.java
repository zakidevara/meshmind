package com.devara.ai.meshmind.config;

import com.devara.ai.meshmind.OnCallAssistant;
import com.devara.ai.meshmind.evaluation.LoggingContentRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenCountEstimator;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class LangChainConfig {

  @Bean
  @ConfigurationProperties(prefix = "langchain4j.google.ai.gemini")
  public GeminiProperties geminiProperties() {
    return new GeminiProperties();
  }

//  @Bean
  public ChatModel ollamaChatModel() {
    return OllamaChatModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("llama3.1")
        .temperature(0.0)
        .timeout(Duration.ofMinutes(5))
        .build();
  }

  @Bean
  @Primary
  public ChatModel geminiChatModel(GeminiProperties properties) {
    return GoogleAiGeminiChatModel.builder()
        .apiKey(properties.getApiKey())
        .modelName(properties.getModelName())
        .temperature(0.0)
        .timeout(Duration.ofSeconds(30))
        .build();
  }

  @Bean
  public EmbeddingModel embeddingModel() {
    return OllamaEmbeddingModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("nomic-embed-text")
        .timeout(Duration.ofMinutes(5))
        .build();
  }

  @Bean
  public EmbeddingStore<TextSegment> embeddingStore() {
    return new InMemoryEmbeddingStore<>(); // change this to dedicated embedding store like lanceDB or pgvector
  }

  @Bean
  public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> store, EmbeddingModel model) {
    ContentRetriever delegate = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(store)
        .embeddingModel(model)
        .maxResults(3)
        .minScore(0.7)
        .build();
    return new LoggingContentRetriever(delegate);
  }

  @Bean
  public ChatMemory chatMemory(GeminiProperties properties) {
    return TokenWindowChatMemory.withMaxTokens(
        2000,
        GoogleAiGeminiTokenCountEstimator.builder()
            .apiKey(properties.getApiKey())
            .modelName(properties.getModelName())
            .build()
    );
  }

  @Bean
  public OnCallAssistant onCallAssistant(ChatModel chatModel, ContentRetriever contentRetriever, ChatMemory chatMemory) {
    return AiServices.builder(OnCallAssistant.class)
        .chatModel(chatModel)
        .chatMemory(chatMemory)
        .contentRetriever(contentRetriever) // enables RAG
        .build();
  }

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Getter
  @Setter
  public static class GeminiProperties {
    private String apiKey;
    private String modelName;
  }
}
