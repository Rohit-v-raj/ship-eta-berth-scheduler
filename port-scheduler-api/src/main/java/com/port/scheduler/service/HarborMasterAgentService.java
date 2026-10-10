package com.port.scheduler.service;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.port.scheduler.components.PortDispatchTools;

@Service 
public class HarborMasterAgentService {
    private final ChatClient chatClient;
    private final PortDispatchTools dispatchTools;

    public HarborMasterAgentService(ChatClient.Builder builder, PortDispatchTools dispatchTools) {
        this.chatClient = builder.build();
        this.dispatchTools = dispatchTools;
    }

    // Type-safe structured output record
    public record DispatchAssessment(
        String vesselStatus, 
        boolean conflictDetected, 
        String operationalRecommendation,
        String noticeToCaptain
    ) {}

    public DispatchAssessment evaluateSchedule(String mmsi, String berthId) {
        return chatClient.prompt()
            .system("""
                You are an autonomous port dispatcher agent.
                Use the tools provided to inspect vessel telemetry and berth occupancy.
                Detect if the vessel will arrive before the targeted berth is cleared.
                Provide a structured operational assessment.
                """)
            .user(String.format("Evaluate arrival feasibility for MMSI: %s heading to Berth: %s", mmsi, berthId))
            .tools(dispatchTools)
            .call()
            .entity(DispatchAssessment.class);
    }

    public record AgentDecision(
        String userPrompt,
        List<String> toolsUsed,
        String reasoningSummary,
        String operationalDecision, // e.g. "PROCEED", "REDUCE_SPEED", "DIVERT_BERTH"
        String instructionsToMaster
    ) {}

    public AgentDecision processPrompt(String userPrompt) {
        String systemInstruction = """
            You are the Autonomous Harbor Master Agent for a deep-water container terminal.
            
            Your job is to read the user's operational query, inspect live data using the available tools,
            and make a concrete operational decision.
            
            Rules:
            1. If a vessel is approaching, check both the vessel telemetry and the intended berth status.
            2. Calculate if the vessel's arrival time creates an overlap with existing occupancy.
            3. Verify physical constraints (vessel draft must be <= berth max draft; LOA must fit).
            4. If a conflict exists, determine whether to instruct speed reduction ('Virtual Arrival') or reassign berth.
            5. Return a complete, structured decision.
            """;

        return chatClient.prompt()
            .system(systemInstruction)
            .user(userPrompt)
            .tools(dispatchTools)
            .call()
            .entity(AgentDecision.class);
    }
}
