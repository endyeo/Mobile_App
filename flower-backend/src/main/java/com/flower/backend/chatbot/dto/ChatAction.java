package com.flower.backend.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 화면 이동(Action) 정보를 담는 DTO.
 * LLM이 navigateScreen Tool을 호출했을 때 생성된다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatAction {

    /** 액션 유형. 현재는 "NAVIGATE"만 지원. */
    private String type;

    /** 이동 대상 화면. "MAP", "COMMUNITY" 등. */
    private String target;

    /** 화면 이동 시 전달할 추가 파라미터. */
    private Map<String, Object> params;
}
