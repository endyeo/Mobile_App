# PR2 플로팅 챗봇 상태 소유권 정리

## Summary
플로팅 챗봇은 메시지와 세션은 static으로 공유하지만, 요청 상태와 스트림 구독은 위젯 인스턴스별로 관리합니다. 화면 이동 후 새 인스턴스가 생기면 메시지 목록과 요청 상태가 분리되어 답변 표시, 중지 버튼, 진행 상태가 어긋날 수 있습니다.

## 문제
- `ChatFloatingButton`의 `_messages`, `_sessionId`, draft 상태는 static입니다.
- `_isSending`, `_activeRequestId`, `_streamSubscription`, `_cancelToken`은 인스턴스 필드입니다.
- 화면마다 플로팅 버튼이 다시 생성되면 같은 대화 내역을 보면서 다른 전송 상태를 가질 수 있습니다.

## 변경 범위
- `flower_app/lib/widgets/chat_floating_button.dart`
  - 플로팅 챗봇 상태를 별도 controller/state holder로 분리하거나, 최소한 static 공유 상태와 인스턴스 상태의 경계를 명확히 정리
  - dispose 시 진행 중 요청을 무조건 끊어야 하는지 정책 결정
  - 화면 이동 후에도 사용자가 진행 중 상태를 일관되게 보도록 처리
- 필요 시 신규 파일
  - `flower_app/lib/services/floating_chat_session_controller.dart`
  - 또는 기존 프로젝트 패턴에 맞는 경량 state holder

## 제외
- 서버 세션 저장 방식 변경
- 액션 실행 순서 변경
- 챗봇 UI 디자인 개편

## 검증
- `flutter analyze --no-pub`
- `flutter test --no-pub`
- 수동 QA
  - 홈에서 질문 후 지도/커뮤니티로 이동
  - 이동 후 플로팅 챗봇 열기
  - 진행 중 중지 버튼 동작
  - 앱 내 여러 화면 이동 후 대화 내역 유지

