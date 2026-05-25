# PR2 커뮤니티 후기 검색 빈 결과와 데이터 모델 정리

## 문제
`수국 후기 찾아줘`에서 사용자는 "수국 후기에 대한 글이 없습니다"를 기대하지만, Flutter에서는 `응답을 가져오지 못했습니다.`가 나왔습니다. 또한 커뮤니티 게시글에는 제목이 없고 글과 사진만 게시되는데, 챗봇/검색 설계가 제목 기반 게시글처럼 보일 수 있습니다.

## 목표
- 커뮤니티 검색 결과가 0건이면 오류가 아니라 "관련 글이 없습니다"로 답합니다.
- 커뮤니티 게시글 검색은 제목이 아니라 본문, 꽃 이름, 식물명, 주소 기준으로 설명합니다.
- 서버 오류와 검색 결과 없음이 사용자 답변에서 명확히 구분됩니다.

## 변경 파일 후보
- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/CommunityAgent/CommunityTools.java`
- `flower-backend/src/main/java/com/flower/backend/community/CommunityPostRepository.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/service/ChatbotService.java`
- `flower_app/lib/widgets/chat_floating_button.dart`
- `flower_app/lib/services/chatbot_service.dart`

## 구현 계획
1. `community.searchPosts` 결과가 0건일 때 `status=SUCCESS`, `items=[]`, `keyword=수국`을 유지합니다.
2. 답변 프롬프트와 guarded answer에서 0건을 오류로 바꾸지 않도록 합니다.
3. `CommunityPostRepository.searchByKeyword`가 현재 본문만 검색한다면, `flowerSpecies`, `plantName`, `address`까지 검색 범위를 맞춥니다.
4. `ToolResult.data`에는 `title`을 만들지 않습니다. 게시글 요약도 `content` 기준으로 작성합니다.
5. Flutter 스트리밍 처리에서 서버가 정상 `FINAL_ANSWER`를 줬는데 이후 이벤트 문제로 `응답을 가져오지 못했습니다.`로 덮어쓰는지 확인합니다.

## 테스트
- 서버 smoke:
  - `.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set community-smoke -ShowDetails`
- 수동 질문:
  - `수국 후기 찾아줘`
  - `벚꽃 커뮤니티 글 보여줘`
  - `장미 게시글 검색해줘`
- 기대:
  - 검색 결과 0건이면 "현재 수국 관련 글이 없습니다" 계열 답변
  - `응답을 가져오지 못했습니다.`가 나오면 실패
  - 제목이라는 표현이 나오면 실패

## 완료 기준
- 검색 결과 없음과 서버 오류가 구분됩니다.
- 커뮤니티 데이터 모델이 제목 없는 게시글 구조로 설명됩니다.
- 관련 글 없음 상황에서도 앱 화면 이동 action은 정상 실행됩니다.

