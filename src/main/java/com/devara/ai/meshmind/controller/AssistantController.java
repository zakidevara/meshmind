package com.devara.ai.meshmind.controller;

import com.devara.ai.meshmind.CustomerSupportAssistant;
import com.devara.ai.meshmind.OnCallAssistant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@Slf4j
@CrossOrigin(origins = "*")
public class AssistantController {

    private final OnCallAssistant onCallAssistant;
    private final CustomerSupportAssistant customerSupportAssistant;

    public AssistantController(OnCallAssistant onCallAssistant, CustomerSupportAssistant customerSupportAssistant) {
        this.onCallAssistant = onCallAssistant;
        this.customerSupportAssistant = customerSupportAssistant;
    }

    @PostMapping("/ask/oncall")
    public String askOncallAssistant(@RequestBody String prompt) {
        String res = onCallAssistant.ask(prompt);
        log.info("Oncall Assistant response: {}", res);
        return res;
    }

    @PostMapping("/ask/customer-support")
    public String askCustomerSupportAssistant(@RequestBody String prompt) {
        String res = customerSupportAssistant.ask(prompt);
        log.info("CS Assistant response: {}", res);
        return res;
    }
}