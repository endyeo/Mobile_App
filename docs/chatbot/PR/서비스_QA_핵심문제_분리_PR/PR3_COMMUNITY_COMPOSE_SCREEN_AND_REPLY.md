# PR3 커뮤니티 작성 화면 연결과 답변 톤 개선

## 문제
후기 글 작성 화면이 바뀌었는데 `AppActionRuntime`은 아직 `COMMUNITY_COMPOSE`를 `CreatePostScreen`으로 열고 있습니다. 실제 커뮤니티 작성은 `CommunityFeedScreen`에서 `CreateFlowerSpotScreen`을 사용합니다. 또한 답변이 "화면을 열어드리겠습니다. 화면을 열었습니다."처럼 딱딱하고 중복됩니다.

## 목표
- 챗봇의 커뮤니티 작성 action이 실제 작성 화면인 `CreateFlowerSpotScreen`을 엽니다.
- 자동 작성/자동 게시 없이 작성 화면만 엽니다.
- 답변은 짧고 자연스럽게, 사용자가 어떤 내용을 쓰면 좋은지 한 문장으로 안내합니다.

## 변경 파일 후보
- `flower_app/lib/app_actions/app_action_runtime.dart`
- `flower_app/lib/screens/create_flower_spot_screen.dart`
- `flower-backend/src/main/java/com/flower/backend/chatbot/service/ChatbotService.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/CommunityAgent/CommunityTools.java`

## 구현 계획
1. `COMMUNITY_COMPOSE` action의 화면을 `CreateFlowerSpotScreen`으로 변경합니다.
2. 필요하면 `CreateFlowerSpotScreen`에 optional `initialTopic` 또는 `assistantHint`를 받도록 설계합니다.
3. v1에서는 입력창에 자동으로 게시글 내용을 채우지 않습니다. 대신 화면 상단 또는 챗봇 답변으로 작성 방향만 안내합니다.
4. 답변 프롬프트를 변경합니다.
   - 나쁜 예: `수국에 대한 후기 글 작성 화면을 열어드리겠습니다. 화면을 열었습니다.`
   - 좋은 예: `수국 후기 작성 화면을 열었어요. 사진을 올리고, 본 장소나 꽃 상태를 짧게 적어보세요.`
5. 스트리밍 중 `ACTION` 진행 메시지와 `FINAL_ANSWER`가 한 말풍선에 어색하게 합쳐지지 않도록 PR4와 연결 지점을 표시합니다.

## 테스트
- 수동 질문:
  - `수국 후기 글 써줘`
  - `커뮤니티에 글 올릴래`
- 기대:
  - `CreateFlowerSpotScreen`이 열립니다.
  - 사진/내용 입력 UI가 현재 앱 작성 화면과 동일합니다.
  - 자동 작성/자동 게시가 없습니다.
  - 챗봇 답변이 중복 없이 자연스럽습니다.

## 완료 기준
- 작성 action이 실제 최신 작성 화면을 엽니다.
- 챗봇 답변에 화면 이동 중복 문장이 없습니다.
- 작성 도움 문구는 제공하지만 게시글 본문을 자동 생성하지 않습니다.

