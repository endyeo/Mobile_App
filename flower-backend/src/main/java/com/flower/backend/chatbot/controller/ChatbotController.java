package com.flower.backend.chatbot.controller;

import com.flower.backend.chatbot.dto.ChatMessageRequest;
import com.flower.backend.chatbot.dto.ChatMessageResponse;
import com.flower.backend.chatbot.service.ChatbotService;
import com.flower.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chatbot")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    /**
     * POST /chatbot/message
     * 사용자 텍스트 메시지를 받아 Agent 응답 반환.
     */
    @PostMapping("/message")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @Valid @RequestBody ChatMessageRequest request
    ) {
        ChatMessageResponse response = chatbotService.chat(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * DELETE /chatbot/session/{sessionId}
     * 대화 세션 초기화.
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> clearSession(
            @PathVariable String sessionId
    ) {
        chatbotService.clearSession(sessionId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
