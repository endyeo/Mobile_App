# PR1 Flutter SSE 파서와 오류 상태 안정화

## Summary
Flutter `ChatbotService.streamMessage`가 SSE data를 항상 유효한 JSON으로 가정하고 있어, 중간 오류나 깨진 이벤트가 들어오면 스트림 전체가 예외로 종료됩니다. 첫 PR은 화면 이동이나 서버 구조를 건드리지 않고, 스트림 파싱과 사용자 오류 상태만 안정화합니다.

## 문제
- `jsonDecode()` 실패가 잡히지 않아 전체 스트림이 끊깁니다.
- 오류 상세가 디버그 문자열 중심이라 사용자 메시지와 개발 로그의 경계가 약합니다.
- `ERROR`, `DONE`, 비정상 종료를 구분하는 UI 상태가 단순합니다.

## 변경 범위
- `flower_app/lib/services/chatbot_service.dart`
  - SSE 이벤트 파싱 실패를 안전하게 무시하거나 `ERROR` 이벤트로 변환
  - 서버 오류 본문과 네트워크 오류를 사용자용 메시지/개발용 로그로 분리
  - `requestId` 없는 이벤트 처리 규칙 명확화
- `flower_app/lib/widgets/chat_floating_button.dart`
  - 스트림 오류, 서버 ERROR, 중지, 비정상 종료 문구를 구분
  - 완료된 답변이 있으면 후속 DONE 누락으로 덮어쓰지 않도록 유지

## 제외
- 화면 이동 타이밍 변경
- 플로팅 챗봇 상태 소유 구조 변경
- 백엔드 SSE executor 변경

## 검증
- `flutter analyze --no-pub`
- `flutter test --no-pub`
- 수동 QA
  - 정상 질문
  - 서버 중단 상태 질문
  - 전송 후 중지
  - 연속 질문

