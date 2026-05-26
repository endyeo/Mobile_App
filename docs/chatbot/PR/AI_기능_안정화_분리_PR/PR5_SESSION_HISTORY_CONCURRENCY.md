# PR5 세션 히스토리 동시성 안정화

## Summary
서버 세션 저장소는 `ConcurrentHashMap`이지만 세션 내부 히스토리는 일반 `ArrayList`입니다. 같은 세션에서 연속 질문이나 중복 요청이 겹치면 히스토리 읽기/쓰기 순서가 꼬일 수 있습니다.

## 문제
- `SessionData.history`가 동시 접근에 안전하지 않습니다.
- 답변 생성 전에 히스토리를 읽고, 답변 후 append하는 흐름에서 요청 순서가 뒤바뀔 수 있습니다.
- requestId는 Flutter stale event 방지에는 쓰이지만 서버 세션 순서 보장에는 쓰이지 않습니다.

## 변경 범위
- `flower-backend/src/main/java/com/flower/backend/chatbot/service/ChatbotService.java`
  - 세션별 synchronized block, lock, copy-on-write 중 하나로 히스토리 접근 보호
  - 히스토리 읽기 snapshot과 append 정책 분리
  - 최대 히스토리 개수 trimming을 동시성 안전하게 처리
  - 빈 메시지/취소된 요청 저장 정책 점검

## 제외
- DB 기반 세션 영속화
- 멀티 인스턴스 서버 간 세션 공유
- 답변 생성 프롬프트 대규모 변경

## 검증
- `.\gradlew.bat test`
- 같은 sessionId로 병렬 요청 테스트 추가
- 히스토리 최대 개수 유지 테스트 추가
- 기존 라우팅/도구 테스트 회귀 확인

