package com.flower.backend.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Flutter 앱 제어 액션 정보를 담는 DTO.
 * NAVIGATE 계열 화면 이동과 MAP_* 계열 지도 내부 요청을 함께 표현한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatAction {

    /** 액션 유형. 예: "NAVIGATE", "MAP_SET_SEARCH_QUERY", "MAP_START_ROUTE". */
    private String type;

    /** 이동 또는 제어 대상. 예: "MAP", "COMMUNITY", "FLOWER_BOOK". */
    private String target;

    /** 화면 이동 또는 지도 액션에 전달할 추가 파라미터. */
    private Map<String, Object> params;
}
