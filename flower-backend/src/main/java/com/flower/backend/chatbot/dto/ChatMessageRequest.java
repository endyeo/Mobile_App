package com.flower.backend.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageRequest {

    @NotBlank(message = "메시지를 입력해주세요.")
    private String message;

    /** 세션 ID. 없으면 서버에서 생성한다. */
    @JsonProperty("session_id")
    private String sessionId;

    /** 사용자 현재 위치. */
    private LocationContext context;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationContext {
        private Double lat;
        private Double lng;
    }
}
