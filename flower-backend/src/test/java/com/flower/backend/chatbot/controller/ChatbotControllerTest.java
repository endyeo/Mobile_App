package com.flower.backend.chatbot.controller;

import com.flower.backend.chatbot.dto.ChatMessageRequest;
import com.flower.backend.chatbot.service.ChatbotService;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatbotControllerTest {

    @Test
    void streamMessageRunsChatStreamOnInjectedExecutor() throws Exception {
        ChatbotService chatbotService = mock(ChatbotService.class);
        CountingExecutor executor = new CountingExecutor();
        ChatbotController controller = new ChatbotController(chatbotService, executor);
        ChatMessageRequest request = new ChatMessageRequest("안녕", "session", "request", null);

        doAnswer(invocation -> {
            ChatbotService.StreamSender sender = invocation.getArgument(1);
            sender.send("DONE", java.util.Map.of("reason", "completed"));
            return null;
        }).when(chatbotService).chatStream(any(), any());

        SseEmitter emitter = controller.streamMessage(request);

        assertThat(emitter).isNotNull();
        assertThat(executor.runCount).isEqualTo(1);
        verify(chatbotService).chatStream(any(), any());
    }

    @Test
    void streamMessageHandlesRejectedExecutorWithoutCallingService() throws Exception {
        ChatbotService chatbotService = mock(ChatbotService.class);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("full");
        };
        ChatbotController controller = new ChatbotController(chatbotService, rejectingExecutor);
        ChatMessageRequest request = new ChatMessageRequest("안녕", "session", "request", null);

        SseEmitter emitter = controller.streamMessage(request);

        assertThat(emitter).isNotNull();
        verify(chatbotService, never()).chatStream(any(), any());
    }

    @Test
    void streamMessageCompletesTerminalErrorWhenServiceFails() throws Exception {
        ChatbotService chatbotService = mock(ChatbotService.class);
        ChatbotController controller = new ChatbotController(chatbotService, Runnable::run);
        ChatMessageRequest request = new ChatMessageRequest("안녕", "session", "request", null);
        doThrow(new RuntimeException("failure")).when(chatbotService).chatStream(any(), any());

        SseEmitter emitter = controller.streamMessage(request);

        assertThat(emitter).isNotNull();
        verify(chatbotService).chatStream(any(), any());
    }

    private static class CountingExecutor implements Executor {
        private int runCount;

        @Override
        public void execute(Runnable command) {
            runCount++;
            command.run();
        }
    }
}
