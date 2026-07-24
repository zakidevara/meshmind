package com.devara.ai.meshmind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OnCallAssistantIntegrationTest {

    @Autowired
    private OnCallAssistant onCallAssistant;

    @Test
    void testRetrievalOfEcsIssue() {
        // Act
        String response = onCallAssistant.ask("How did we fix the ECS task crash-looping with OOMKilled?");

        // Assert
        // The LLM's exact phrasing varies, but it must include the core factual resolution
        assertThat(response).containsIgnoringCase("memory");
        assertThat(response).containsIgnoringCase("4GB");
    }
}