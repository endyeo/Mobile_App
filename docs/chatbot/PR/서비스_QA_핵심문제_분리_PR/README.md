# 서비스 QA 핵심 문제 분리 PR 계획

## Summary
Flutter 수동 QA에서 확인된 문제는 단순 챗봇 문구 문제가 아니라, 화면 제공 계약, 스트리밍 처리, 커뮤니티 데이터 모델 이해, 지도 상태 반영이 섞여 있습니다. 한 번에 수정하면 어떤 변경이 어떤 문제를 해결했는지 검증하기 어렵기 때문에 PR을 6개로 분리합니다.

## PR 순서
1. `PR1_FLOWER_BOOK_FLOATING_AI.md`
   - 꽃 도감 화면에 AI 플로팅 버튼 노출
2. `PR2_COMMUNITY_SEARCH_EMPTY_RESULT.md`
   - 커뮤니티 후기 검색의 빈 결과/오류 구분, 제목 없는 게시글 구조 반영
3. `PR3_COMMUNITY_COMPOSE_SCREEN_AND_REPLY.md`
   - 변경된 후기 작성 화면 연결, 작성 화면 답변 톤 개선
4. `PR4_CHAT_STREAM_STALE_REPLY_FIX.md`
   - 이전 질문 답변이 다음 질문에 섞이는 스트리밍/세션 꼬임 방지
5. `PR5_MAP_QUERY_AND_ROUTE_STATE.md`
   - 지도 검색어 반영, 중복 지도 열기, 길찾기 답변 분리
6. `PR6_CURRENT_SCREEN_CONTEXT_CONTRACT.md`
   - 현재 사용자가 어느 화면에 있는지 서버에 전달하는 컨텍스트 계약 추가

## 공통 원칙
- 보안 개선은 이번 묶음에서 제외합니다.
- 사용자가 보는 답변에는 내부 route/tool/action/agent/debug 값을 노출하지 않습니다.
- 앱 action은 화면 이동 자체보다 화면 상태 변화까지 확인합니다.
- 커뮤니티 게시글은 제목이 없고 `content`, `image`, `flowerSpecies/plantName`, `address` 중심입니다.
- 작성, 좋아요, 댓글, 삭제, 구매, 퀘스트 인증 같은 쓰기 작업은 자동 실행하지 않습니다.

