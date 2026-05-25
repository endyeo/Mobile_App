# PR4 챗봇 스트리밍 이전 답변 섞임 방지

## 문제
`수국 후기 글 써줘` 이후 `벚꽃 지도에서 보여줘`를 물었을 때 지도는 이동했지만 답변은 이전 수국 후기 작성 답변이 나왔습니다. 이는 서버 planner 문제일 수도 있지만, Flutter 스트리밍 처리에서 이전 요청의 이벤트가 다음 요청 말풍선을 덮는 문제일 가능성이 큽니다.

## 목표
- 각 사용자 질문마다 고유 request id를 부여합니다.
- 이전 스트림 이벤트가 새 질문 UI를 덮지 못하게 합니다.
- 새 질문 전송 시 이전 스트림은 명확히 cancel합니다.
- `ACTION`, `TOOL_RESULT`, `FINAL_ANSWER`, `DONE` 순서가 달라도 최종 답변이 뒤섞이지 않게 합니다.

## 변경 파일 후보
- `flower_app/lib/widgets/chat_floating_button.dart`
- `flower_app/lib/services/chatbot_service.dart`
- `flower-backend/src/main/java/com/flower/backend/chatbot/service/ChatbotService.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/dto/ChatMessageRequest.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/dto/ChatMessageResponse.java`

## 구현 계획
1. Flutter에서 메시지 전송 시 `requestId`를 생성합니다.
2. SSE 요청 payload에 `requestId`를 포함합니다.
3. 서버 SSE 이벤트에도 같은 `requestId`를 포함합니다.
4. Flutter는 현재 활성 `requestId`와 다른 이벤트를 무시합니다.
5. `_streamSubscription`이 남아 있는 상태에서 새 메시지를 보내면 이전 subscription을 먼저 cancel합니다.
6. `FINAL_ANSWER` 수신 이후 `ERROR`나 `onDone`이 정상 답변을 덮지 못하게 합니다.
7. `ACTION` 진행 문구는 최종 답변과 별도 상태로 처리하거나, 최종 답변이 오면 반드시 교체합니다.

## 테스트
- 수동 재현:
  - `수국 후기 글 써줘`
  - 화면 이동 직후 바로 `벚꽃 지도에서 보여줘`
- 기대:
  - 두 번째 답변은 반드시 벚꽃 지도 관련 답변
  - 검색창에 `벚꽃` 반영
  - 이전 수국 작성 답변이 다시 나오면 실패
- 추가:
  - 빠르게 3개 질문 연속 입력
  - 중지 버튼 후 새 질문 입력

## 완료 기준
- 이전 질문의 SSE 이벤트가 다음 질문 말풍선을 덮지 않습니다.
- action은 맞는데 답변이 다른 질문인 상황이 사라집니다.
- `응답을 가져오지 못했습니다.`가 정상 최종 답변을 덮지 않습니다.

