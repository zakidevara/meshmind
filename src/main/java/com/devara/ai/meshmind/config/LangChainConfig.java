package com.devara.ai.meshmind.config;

import com.devara.ai.meshmind.OnCallAssistant;
import com.devara.ai.meshmind.SlackThreadSummarizer;
import com.devara.ai.meshmind.evaluation.LoggingContentRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
  @ConfigurationProperties(prefix = "langchain4j.open-ai")
  public OpenAiProperties openAiProperties() {
    return new OpenAiProperties();
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
  @ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai", matchIfMissing = true)
  public ChatModel openAiChatModel(OpenAiProperties properties) {
    return OpenAiChatModel.builder()
        .apiKey(properties.getApiKey())
        .modelName(properties.getModelName())
        .temperature(0.0)
        .timeout(Duration.ofSeconds(30))
        .build();
  }

  @Bean
  @ConditionalOnProperty(name = "app.llm.provider", havingValue = "gemini")
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
  @ConfigurationProperties(prefix = "app.milvus")
  public MilvusProperties milvusProperties() {
    return new MilvusProperties();
  }

  @Bean
  public EmbeddingStore<TextSegment> embeddingStore(MilvusProperties props) {
    return MilvusEmbeddingStore.builder()
        .host(props.getHost())
        .port(props.getPort())
        .collectionName(props.getCollectionName())
        .dimension(props.getDimension())
        .build();
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
  public ChatMemory chatMemory() {
    return MessageWindowChatMemory.withMaxMessages(20);
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
  public SlackThreadSummarizer slackThreadSummarizer(ChatModel chatModel) {
    return AiServices.builder(SlackThreadSummarizer.class)
        .chatModel(chatModel)
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

  @Getter
  @Setter
  public static class OpenAiProperties {
    private String apiKey;
    private String modelName;
  }

  @Getter
  @Setter
  public static class MilvusProperties {
    private String host = "localhost";
    private Integer port = 19530;
    private String collectionName = "meshmind_slack";
    private Integer dimension = 768; // must match the embedding model's output dim (nomic-embed-text = 768)
  }
}
