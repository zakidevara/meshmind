package com.devara.ai.meshmind.controller;

import com.devara.ai.meshmind.OnCallAssistant;
import com.devara.ai.meshmind.evaluation.RagEvaluationLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/ai")
@Slf4j
@CrossOrigin(origins = "*")
public class AssistantController {

    private final OnCallAssistant onCallAssistant;
    private final RagEvaluationLogger evaluationLogger;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    public AssistantController(OnCallAssistant onCallAssistant, RagEvaluationLogger evaluationLogger) {
        this.onCallAssistant = onCallAssistant;
        this.evaluationLogger = evaluationLogger;
    }

    @PostMapping("/ask/oncall")
    public String askOncallAssistant(@RequestBody String prompt) {
        String res = onCallAssistant.ask(prompt);
        CompletableFuture.runAsync(() -> evaluationLogger.log(prompt, res), executorService);
        return res;
    }
}