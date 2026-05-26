package com.flower.backend.chatbot.controller;

import com.flower.backend.chatbot.dto.ChatMessageRequest;
import com.flower.backend.chatbot.dto.ChatMessageResponse;
import com.flower.backend.chatbot.service.ChatbotService;
import com.flower.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
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
public class ChatbotController {

    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final ChatbotService chatbotService;
    private final Executor chatbotSseExecutor;

    public ChatbotController(
            ChatbotService chatbotService,
            @Qualifier("chatbotSseExecutor") Executor chatbotSseExecutor
    ) {
        this.chatbotService = chatbotService;
        this.chatbotSseExecutor = chatbotSseExecutor;
    }

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
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        String requestId = request.getRequestId() == null ? "" : request.getRequestId();
        RequestAttributes requestAttributes = RequestContextHolder.currentRequestAttributes();

        emitter.onTimeout(() -> {
            log.warn("SSE 챗봇 스트림 타임아웃: requestId={}", requestId);
            sendTerminalError(
                    emitter,
                    closed,
                    requestId,
                    "챗봇 응답 시간이 초과되었습니다. 다시 시도해 주세요.",
                    "timeout"
            );
        });
        emitter.onCompletion(() -> closed.set(true));
        emitter.onError(error -> {
            closed.set(true);
            log.warn("SSE 챗봇 스트림 연결 오류: requestId={}, error={}",
                    requestId,
                    error == null ? "unknown" : error.getClass().getSimpleName());
        });

        try {
            CompletableFuture.runAsync(() -> {
                RequestContextHolder.setRequestAttributes(requestAttributes);
                try {
                    chatbotService.chatStream(request, (eventName, data) ->
                            sendEvent(emitter, closed, eventName, data));
                    complete(emitter, closed);
                } catch (Exception e) {
                    log.error("SSE 챗봇 스트림 처리 실패", e);
                    sendTerminalError(
                            emitter,
                            closed,
                            requestId,
                            "챗봇 처리 중 오류가 발생했습니다.",
                            "error"
                    );
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            }, chatbotSseExecutor);
        } catch (RejectedExecutionException e) {
            log.error("SSE 챗봇 executor 작업 등록 실패", e);
            sendTerminalError(
                    emitter,
                    closed,
                    requestId,
                    "챗봇 요청이 많아 잠시 처리할 수 없습니다. 다시 시도해 주세요.",
                    "rejected"
            );
        }

        return emitter;
    }

    private void sendEvent(
            SseEmitter emitter,
            AtomicBoolean closed,
            String eventName,
            Object data
    ) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            if (closed.compareAndSet(false, true)) {
                emitter.completeWithError(e);
            }
            throw new IllegalStateException("SSE 이벤트 전송 실패: " + eventName, e);
        }
    }

    private void sendTerminalError(
            SseEmitter emitter,
            AtomicBoolean closed,
            String requestId,
            String message,
            String reason
    ) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("ERROR")
                    .data(Map.of(
                            "stage", "ERROR",
                            "message", message,
                            "requestId", requestId,
                            "request_id", requestId
                    )));
            emitter.send(SseEmitter.event()
                    .name("DONE")
                    .data(Map.of(
                            "reason", reason,
                            "requestId", requestId,
                            "request_id", requestId
                    )));
            emitter.complete();
        } catch (Exception sendError) {
            emitter.completeWithError(sendError);
        }
    }

    private void complete(SseEmitter emitter, AtomicBoolean closed) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                log.warn("SSE 챗봇 스트림 완료 처리 실패", ignored);
            }
        }
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
