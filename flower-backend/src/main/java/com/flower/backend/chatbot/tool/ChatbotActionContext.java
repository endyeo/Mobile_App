package com.flower.backend.chatbot.tool;

import com.flower.backend.chatbot.dto.ChatAction;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HTTP Request 단위로 생성되어 안전하게 상태를 관리하는 컨텍스트.
 * ThreadLocal의 데이터 유실/덮어쓰기 문제를 해결합니다.
 */
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class ChatbotActionContext {

    // 화면 이동 등 여러 액션이 발생할 수 있으므로 리스트로 관리 (Main Agent 복합 응답 시 여러 액션 취합 대비)
    private final List<ChatAction> actions = new CopyOnWriteArrayList<>();

    // 도구 호출 횟수 저장
    private final Map<String, Integer> toolCounts = new ConcurrentHashMap<>();

    // 검색 종류(searchFlowers, searchPosts 등) 도구가 하나라도 호출되었는지 여부
    private volatile boolean searchToolInvoked = false;

    // 사용자 위치 정보
    private double lat, lng;

    public void addAction(ChatAction action) {
        actions.add(action);
    }

    public List<ChatAction> getActions() {
        return actions;
    }

    /**
     * 우선순위가 가장 높은 1개의 액션만 가져온다. (현행 호환용)
     * 실제로는 클라이언트가 복수를 처리 가능하도록 나중에 확장 가능.
     */
    public ChatAction getPrimaryAction() {
        if (actions.isEmpty())
            return null;
        return actions.get(0);
    }

    public void markSearchInvoked() {
        this.searchToolInvoked = true;
    }

    public boolean isSearchToolInvoked() {
        return searchToolInvoked;
    }

    public void incrementToolCount(String toolName) {
        toolCounts.compute(toolName, (k, v) -> (v == null) ? 1 : v + 1);
    }

    public Map<String, Integer> getToolCounts() {
        return toolCounts;
    }

    // 사용자 위치 정보 getter/setter
    public double getlat() {
        return lat;
    }

    public double getlng() {
        return lng;
    }

    public void setlat(double lat) {
        this.lat = lat;
    }

    public void setlng(double lng) {
        this.lng = lng;
    }
}
