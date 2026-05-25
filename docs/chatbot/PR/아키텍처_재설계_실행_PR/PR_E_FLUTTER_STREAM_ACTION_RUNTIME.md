# PR-E Flutter 스트림과 Action Runtime 안정화

## 문제
Flutter 수동 QA에서 이전 질문의 답변이 다음 질문에 섞이고, 지도 검색어가 화면에 반영되지 않으며, 꽃 도감 화면에는 AI 플로팅 버튼이 없습니다. 또한 작성 action이 최신 작성 화면과 맞지 않습니다.

## 목표
Flutter 쪽에서 챗봇 응답과 action 실행을 안정화합니다.

## 구현 계획
- 각 챗봇 요청에 `requestId`를 생성합니다.
- SSE 이벤트에도 `requestId`를 포함하고, Flutter는 현재 요청과 다른 이벤트를 무시합니다.
- 새 질문 전송 전 기존 stream subscription을 cancel합니다.
- `FINAL_ANSWER`를 받은 뒤 `ERROR`나 `onDone`이 정상 답변을 덮지 못하게 합니다.
- `COMMUNITY_COMPOSE` action은 `CreateFlowerSpotScreen`으로 연결합니다.
- `MAP_SET_SEARCH_QUERY`가 검색창과 지도 JS 상태에 모두 반영되는지 확인합니다.
- `FlowerBookPage`에 `ChatFloatingButton`을 추가합니다.

## 테스트
- `수국 후기 글 써줘` 직후 `벚꽃 지도에서 보여줘`
  - 답변이 섞이지 않아야 함
  - 지도 검색창에 `벚꽃` 반영
- 도감 화면 진입
  - AI 플로팅 버튼 표시
- `커뮤니티에 글 올릴래`
  - 최신 작성 화면 표시
- 정상 답변 후 `응답을 가져오지 못했습니다.`로 덮이면 실패

## 완료 기준
- 이전 질문의 스트림 이벤트가 새 질문 답변을 덮지 않습니다.
- action이 실제 Flutter 화면 상태를 바꿉니다.
- 도감/지도/커뮤니티 작성 화면에서 챗봇 UX가 끊기지 않습니다.

