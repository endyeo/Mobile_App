package com.flower.backend.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
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
public class AgentRunTrace {

    private String mode;
    private String route;
    private String specialist;

    @Builder.Default
    private List<AgentStepTrace> steps = new ArrayList<>();

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentStepTrace {
        private int step;
        private String agent;
        private String tool;
        private String status;
        private String message;
    }
}
