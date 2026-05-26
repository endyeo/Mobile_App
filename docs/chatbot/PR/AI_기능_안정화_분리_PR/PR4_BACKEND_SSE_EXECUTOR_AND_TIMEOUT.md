# PR4 백엔드 SSE Executor와 타임아웃 분리

## Summary
백엔드 SSE 엔드포인트는 `CompletableFuture.runAsync()` 기본 common pool에서 챗봇 처리를 실행합니다. AI 호출과 도구 조회는 오래 걸릴 수 있으므로, 챗봇 전용 executor와 연결 종료 처리를 분리해야 운영 부하를 예측할 수 있습니다.

## 문제
- 기본 common pool 사용으로 다른 비동기 작업과 자원을 공유합니다.
- 클라이언트 연결 종료, timeout, error callback 정책이 명확하지 않습니다.
- SSE 작업이 늦어질 때 사용자에게 어떤 이벤트를 보낼지 기준이 약합니다.

## 변경 범위
- `flower-backend/src/main/java/com/flower/backend/chatbot/controller/ChatbotController.java`
  - 챗봇 SSE 전용 executor 사용
  - `SseEmitter.onTimeout`, `onCompletion`, `onError` 등록
  - 이미 완료된 emitter에 중복 전송하지 않도록 보호
- 신규 설정 파일 또는 config
  - 챗봇 executor thread pool 크기와 queue 정책 설정
  - 운영 설정값은 환경별 조정 가능하게 구성

## 제외
- AI planner 프롬프트 변경
- 도구 조회 로직 변경
- Flutter 액션 실행 정책 변경

## 검증
- `.\gradlew.bat test`
- 동시 SSE 요청 수동 확인
- 클라이언트 중지/연결 종료 시 서버 로그 확인
- timeout 시 `ERROR`와 `DONE` 계약이 유지되는지 확인

