package com.devara.ai.meshmind;

import dev.langchain4j.service.SystemMessage;

public interface CustomerSupportAssistant {
  @SystemMessage({
      "You are an customer support assistant.",
      "Answer the user's question using ONLY the provided context from past customer support tickets.",
      "If the answer is not in the context, reply exactly with: 'I cannot find this information in past support tickets.'"
  })
  String ask(String userMessage);
}
