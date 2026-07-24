package com.devara.ai.meshmind.config;

import com.devara.ai.meshmind.OnCallAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LangChainConfig {

  @Bean
  @ConfigurationProperties(prefix = "langchain4j.google.ai.gemini")
  public GeminiProperties geminiProperties() {
    return new GeminiProperties();
  }

  @Bean
  public ChatModel ollamaChatModel(GeminiProperties properties) {
//    return GoogleAiGeminiChatModel.builder()
//        .apiKey(properties.getApiKey())
//        .modelName(properties.getModelName())
//        .temperature(0.0)
//        .timeout(Duration.ofSeconds(30))
//        .build();
    return OllamaChatModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("llama3.1")
        .temperature(0.0)
        .timeout(Duration.ofMinutes(5))
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
  public EmbeddingStore embeddingStore() {
    return new InMemoryEmbeddingStore<>();
  }

  @Bean
  public ContentRetriever contentRetriever(EmbeddingStore store, EmbeddingModel model) {
    return EmbeddingStoreContentRetriever.builder()
        .embeddingStore(store)
        .embeddingModel(model)
        .maxResults(3) // fetch top 3 relevant chunks
        .minScore(0.7) // only consider chunks with 70% relevancy
        .build();
  }

  @Bean
  public OnCallAssistant wikiAssistant(ChatModel chatModel, ContentRetriever contentRetriever) {
    return AiServices.builder(OnCallAssistant.class)
        .chatModel(chatModel)
        .contentRetriever(contentRetriever) // enables RAG
        .build();
  }

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  public static class GeminiProperties {
    private String apiKey;
    private String modelName;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
  }
}
