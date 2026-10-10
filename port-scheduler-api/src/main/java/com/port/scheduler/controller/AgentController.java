package com.port.scheduler.controller;

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

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final ChatClient chatClient;
    private final HarborMasterAgentService agentService;

    public AgentController(ChatClient.Builder chatClientBuilder, HarborMasterAgentService agentService) {
        this.chatClient = chatClientBuilder.build();
        this.agentService = agentService;
    }

    @GetMapping("/ping")
    public String ping(@RequestParam(defaultValue = "Say hello from Gemini in Spring Boot") String prompt) {
        System.out.println("Received prompt: " + prompt);
        String response = chatClient.prompt()
            .user(prompt)
            .call()
            .content();
        System.out.println("Generated response: " + response);
        return response;
    }

    public record ChatQuery(String message) {}

    @PostMapping("/chat")
    public AgentDecision chatWithAgent(@RequestBody ChatQuery query) {
        return agentService.processPrompt(query.message());
    }

    public record DispatchRequest(String mmsi, String berthId) {}

    @PostMapping("/evaluate")
    public ResponseEntity<DispatchAssessment> evaluateVesselSchedule(@RequestBody DispatchRequest request) {
        DispatchAssessment assessment = agentService.evaluateSchedule(
            request.mmsi(), 
            request.berthId()
        );
        return ResponseEntity.ok(assessment);
    }
}
