package com.port.scheduler.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.port.scheduler.service.HarborMasterAgentService;
import com.port.scheduler.service.HarborMasterAgentService.AgentDecision;
import com.port.scheduler.service.HarborMasterAgentService.DispatchAssessment;

/**
 * The harbor-master agent (Gemini). What is asked and answered is never
 * logged, only its size: a prompt can hold anything a person typed.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final ChatClient chatClient;
    private final HarborMasterAgentService agentService;

    public AgentController(ChatClient.Builder chatClientBuilder, HarborMasterAgentService agentService) {
        this.chatClient = chatClientBuilder.build();
        this.agentService = agentService;
    }

    @GetMapping("/ping")
    public String ping(@RequestParam(defaultValue = "Say hello from Gemini in Spring Boot") String prompt) {
        String response = chatClient.prompt()
            .user(prompt)
            .call()
            .content();
        log.info("Agent ping: a prompt of {} characters, an answer of {}", length(prompt), length(response));
        return response;
    }

    public record ChatQuery(String message) {}

    @PostMapping("/chat")
    public AgentDecision chatWithAgent(@RequestBody ChatQuery query) {
        AgentDecision decision = agentService.processPrompt(query.message());
        log.info("Agent chat: a message of {} characters answered", length(query.message()));
        return decision;
    }

    public record DispatchRequest(String mmsi, String berthId) {}

    @PostMapping("/evaluate")
    public ResponseEntity<DispatchAssessment> evaluateVesselSchedule(@RequestBody DispatchRequest request) {
        DispatchAssessment assessment = agentService.evaluateSchedule(
            request.mmsi(), 
            request.berthId()
        );
        log.info("Agent evaluation of a vessel against a berth answered");
        return ResponseEntity.ok(assessment);
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }
}
