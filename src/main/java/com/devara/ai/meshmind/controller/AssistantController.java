package com.devara.ai.meshmind.controller;

import com.devara.ai.meshmind.OnCallAssistant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@Slf4j
@CrossOrigin(origins = "*")
public class AssistantController {

    private final OnCallAssistant onCallAssistant;

    public AssistantController(OnCallAssistant onCallAssistant) {
        this.onCallAssistant = onCallAssistant;
    }

    @PostMapping("/ask")
    public String askAssistant(@RequestBody String prompt) {
        String res = onCallAssistant.ask(prompt);
        log.info("Assistant response: {}", res);
        return res;
    }
}