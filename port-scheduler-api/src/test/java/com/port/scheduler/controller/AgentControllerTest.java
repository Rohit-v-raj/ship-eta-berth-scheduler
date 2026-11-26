package com.port.scheduler.controller;

import com.port.scheduler.service.HarborMasterAgentService;
import com.port.scheduler.service.HarborMasterAgentService.AgentDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The agent's routes log only the size of what was asked and answered, never the text: a prompt can hold anything. */
@WebMvcTest(AgentController.class)
@ExtendWith(OutputCaptureExtension.class)
class AgentControllerTest {

    private static final String PROMPT = "Is the berth for the Kvitsoy free at noon";
    private static final String ANSWER = "Berth B2 is free from 11:00";

    @Autowired MockMvc mvc;
    // the controller takes its client from the builder once, so the stubs must outlive each test
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS, reset = MockReset.NONE) ChatClient.Builder chatClientBuilder;
    @MockitoBean HarborMasterAgentService agentService;

    @Test
    void aPromptAndItsAnswerAreNotLogged(CapturedOutput log) throws Exception {
        given(chatClientBuilder.build().prompt().user(anyString()).call().content()).willReturn(ANSWER);

        mvc.perform(get("/api/agent/ping").param("prompt", PROMPT))
                .andExpect(status().isOk())
                .andExpect(content().string(ANSWER));

        assertThat(log.getOut())
                .contains("Agent ping: a prompt of " + PROMPT.length() + " characters, an answer of " + ANSWER.length())
                .doesNotContain("Kvitsoy")
                .doesNotContain(ANSWER);
    }

    @Test
    void aChatMessageIsNotLogged(CapturedOutput log) throws Exception {
        given(agentService.processPrompt(any())).willReturn(
                new AgentDecision(PROMPT, List.of(), "summary", "PROCEED", "Proceed at 10 knots"));

        mvc.perform(post("/api/agent/chat").contentType("application/json")
                        .content("{\"message\":\"" + PROMPT + "\"}"))
                .andExpect(status().isOk());

        assertThat(log.getOut())
                .contains("Agent chat: a message of " + PROMPT.length() + " characters answered")
                .doesNotContain("Kvitsoy");
    }
}
