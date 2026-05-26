# PR3 앱 액션 실행 타이밍 정리

## Summary
현재 플로팅 챗봇은 SSE `ACTION` 이벤트를 받는 즉시 화면을 이동합니다. 그 뒤 `FINAL_ANSWER`가 오면 이전 화면의 위젯 인스턴스에서 처리될 수 있어, 사용자는 화면은 이동했지만 답변이 보이지 않거나 늦게 갱신되는 경험을 할 수 있습니다.

## 문제
- `ACTION` 이벤트 처리 중 바로 `AppActionRuntime.execute()`를 호출합니다.
- `FINAL_ANSWER`와 화면 이동의 순서가 사용자 경험 기준으로 정리되어 있지 않습니다.
- 한 응답에서 여러 액션이 올 때 어떤 시점에 어떤 액션을 실행할지 계약이 약합니다.

## 변경 범위
- `flower_app/lib/widgets/chat_floating_button.dart`
  - ACTION은 즉시 실행하지 않고 pending action으로 보관
  - FINAL_ANSWER 표시 후 액션 실행 또는 사용자가 볼 수 있는 짧은 지연 후 실행
  - DONE만 오고 FINAL_ANSWER가 없는 경우 액션 실행 여부 정책 정리
- `flower_app/lib/app_actions/app_action_runtime.dart`
  - 액션 실행 실패 메시지와 무시 조건 정리
  - 지도 액션과 일반 화면 액션 우선순위 문서화

## 제외
- 지도 내부 액션 처리 로직 변경
- 커뮤니티/도감/산책 화면 내부 구현 변경
- 서버 액션 생성 정책 변경

## 검증
- `flutter analyze --no-pub`
- `flutter test --no-pub`
- 수동 QA
  - "벚꽃 명소 지도에서 보여줘"
  - "수국 후기 찾아줘"
  - "커뮤니티에 글 올릴래"
  - 액션 있는 질문 직후 답변이 대화창에 남는지 확인

