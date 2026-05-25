# PR6 현재 화면 컨텍스트 계약 추가

## 문제
챗봇이 현재 사용자가 어느 화면을 보고 있는지 모르는 상태입니다. 그래서 이미 지도에 있는데 또 지도를 열거나, 작성 화면에서 다음 질문을 했을 때 이전 화면 맥락과 새 질문 맥락이 섞이는 문제가 발생합니다.

## 목표
- Flutter가 현재 화면 정보를 챗봇 요청 context에 포함합니다.
- 서버는 현재 화면을 action 생성의 보조 정보로만 사용합니다.
- 현재 화면 컨텍스트가 없어도 기존 동작은 유지됩니다.

## 컨텍스트 예시
```json
{
  "currentScreen": "MAP",
  "currentQuery": "수국",
  "currentPostMode": "compose",
  "currentFlowerBookQuery": "장미"
}
```

## 변경 파일 후보
- `flower_app/lib/services/chatbot_service.dart`
- `flower_app/lib/widgets/chat_floating_button.dart`
- `flower_app/lib/app_actions/app_action_runtime.dart`
- `flower_app/lib/screens/kakao_map_screen.dart`
- `flower_app/lib/screens/community_feed_screen.dart`
- `flower_app/lib/screens/flower_book_page.dart`
- `flower-backend/src/main/java/com/flower/backend/chatbot/dto/ChatMessageRequest.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/service/ChatbotService.java`

## 구현 계획
1. Flutter 전역 또는 route observer로 현재 화면 이름을 관리합니다.
2. 챗봇 요청 시 `context.currentScreen`을 보냅니다.
3. 지도/커뮤니티/도감은 가능하면 현재 query도 함께 보냅니다.
4. 서버 action 생성 규칙:
   - 현재 화면이 `MAP`이고 다음 action도 `NAVIGATE MAP`이면 navigate를 생략하고 상태 action만 내려도 됩니다.
   - 현재 화면이 `COMMUNITY_COMPOSE`이고 새 질문이 지도 질문이면 이전 작성 답변을 이어가지 않습니다.
   - 현재 화면 정보는 planner 결정의 1차 근거가 아니라 action 최적화 보조 정보입니다.
5. 로그/평가 결과에 `currentScreen`을 표시해 Flutter 실행 문제와 AI 계획 문제를 구분합니다.

## 테스트
- 지도 화면에서:
  - `벚꽃 지도에서 보여줘`
  - 기대: 같은 지도 화면에서 검색어만 갱신
- 커뮤니티 작성 화면에서:
  - `벚꽃 지도에서 보여줘`
  - 기대: 새 질문으로 처리, 이전 작성 답변 미노출
- 도감 화면에서:
  - `수국 후기 찾아줘`
  - 기대: 커뮤니티로 이동하고 query 반영

## 완료 기준
- 챗봇 요청에 현재 화면 정보가 포함됩니다.
- 중복 화면 이동이 줄어듭니다.
- 현재 화면 때문에 잘못된 답변이 이어지는 문제가 줄어듭니다.

