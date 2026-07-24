package com.devara.ai.meshmind;

import dev.langchain4j.service.SystemMessage;

public interface OnCallAssistant {
  @SystemMessage({
      "You are an on-call engineer assistant.",
      "Answer the user's question using ONLY the provided context from company wikis and chats.",
      "If the answer is not in the context, reply exactly with: 'I cannot find this information in the internal knowledge base.'"
  })
  String ask(String userMessage);
}
