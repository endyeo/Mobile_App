package com.flower.backend.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessageResponse {

    private String reply;

    // Backward-compatible primary action for the existing Flutter UI.
    private ChatAction action;

    private List<ChatAction> actions;

    private AgentRunTrace agentRun;

    private List<ToolResult> toolResults;

    private String sessionId;

    @JsonProperty("request_id")
    private String requestId;
}
