package com.devara.ai.meshmind.controller;

import com.devara.ai.meshmind.OnCallAssistant;
import com.devara.ai.meshmind.evaluation.RagEvaluationLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@Slf4j
@CrossOrigin(origins = "*")
public class AssistantController {

    private final OnCallAssistant onCallAssistant;
    private final RagEvaluationLogger evaluationLogger;

    public AssistantController(OnCallAssistant onCallAssistant, RagEvaluationLogger evaluationLogger) {
        this.onCallAssistant = onCallAssistant;
        this.evaluationLogger = evaluationLogger;
    }

    @PostMapping("/ask/oncall")
    public String askOncallAssistant(@RequestBody String prompt) {
        String res = onCallAssistant.ask(prompt);
        evaluationLogger.log(prompt, res);
        return res;
    }
}