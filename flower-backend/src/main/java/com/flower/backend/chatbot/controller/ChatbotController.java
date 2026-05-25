package com.flower.backend.chatbot.controller;

import com.flower.backend.chatbot.dto.ChatMessageRequest;
import com.flower.backend.chatbot.dto.ChatMessageResponse;
import com.flower.backend.chatbot.service.ChatbotService;
import com.flower.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/chatbot")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    /**
     * POST /chatbot/message
     * 사용자 텍스트 메시지를 받아 챗봇 응답을 반환한다.
     */
    @PostMapping("/message")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @Valid @RequestBody ChatMessageRequest request
    ) {
        ChatMessageResponse response = chatbotService.chat(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * POST /chatbot/message/stream
     * 사용자 텍스트 메시지를 받아 Agent 진행 상태와 최종 응답을 SSE로 순차 전송한다.
     */
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @Valid @RequestBody ChatMessageRequest request
    ) {
        SseEmitter emitter = new SseEmitter(120_000L);

        CompletableFuture.runAsync(() -> {
            try {
                chatbotService.chatStream(request, (eventName, data) ->
                        emitter.send(SseEmitter.event().name(eventName).data(data)));
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 챗봇 스트림 처리 실패", e);
                String requestId = request.getRequestId() == null ? "" : request.getRequestId();
                try {
                    emitter.send(SseEmitter.event()
                            .name("ERROR")
                            .data(Map.of(
                                    "stage", "ERROR",
                                    "message", "챗봇 처리 중 오류가 발생했습니다.",
                                    "requestId", requestId,
                                    "request_id", requestId
                            )));
                    emitter.send(SseEmitter.event()
                            .name("DONE")
                            .data(Map.of(
                                    "reason", "error",
                                    "requestId", requestId,
                                    "request_id", requestId
                            )));
                    emitter.complete();
                } catch (Exception sendError) {
                    emitter.completeWithError(sendError);
                }
            }
        });

        return emitter;
    }

    /**
     * DELETE /chatbot/session/{sessionId}
     * 대화 세션을 초기화한다.
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> clearSession(
            @PathVariable String sessionId
    ) {
        chatbotService.clearSession(sessionId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
