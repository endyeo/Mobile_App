package com.flower.backend.chatbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Main Agent(Router)가 사용자의 질문을 분석한 단계별 오케스트레이션 수행 계획.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePlan {
    /**
     * 질문 유형 구분
     * "SIMPLE": 단일 에이전트로 처리 가능 (1단계)
     * "COMPLEX": 여러 에이전트 혹은 순차적인 도움이 필요한 복합 질의
     * "GREETING": 일반적인 인사 등 도구가 필요 없는 질문
     */
    private String type;

    /**
     * 순차적으로 실행되어야 하는 작업 단계(Step)들의 목록.
     * 인덱스(Step) 순서대로 실행되며, 이전 Step의 결과가 다음 Step으로 전달됩니다.
     */
    private List<RouteStep> steps = new ArrayList<>();

    @Data // getter/setter를 자동 생성 해줌
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteStep {
        /** 현재 작업의 단계 번호 (1, 2, 3...) */
        private int step;

        /** 해당 단계에서 '동시(병렬)'에 실행될 에이전트의 이름 배열 */
        private List<String> agents = new ArrayList<>();

        /** 해당 단계에서 에이전트들이 수행해야 하는 구체적인 지시사항 */
        private String instruction;
    }
}
