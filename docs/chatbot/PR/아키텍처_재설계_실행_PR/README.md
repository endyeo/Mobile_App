# 챗봇 아키텍처 재설계 실행 PR

## Summary
현재 챗봇은 하나의 planner가 route, task, tool, action을 한 번에 고르는 구조라서 서비스 QA에서 답변 섞임, 화면 상태 미반영, 정보 없음/오류 혼동, 커뮤니티 작성 의도 오해가 발생했습니다. 이 폴더는 해당 문제를 한 번에 고치지 않고, 큰 아키텍처를 단계별 PR로 쪼개기 위한 실행 계획입니다.

## 목표 구조
```text
Flutter
→ Chatbot API
→ Route AI
→ Flow Orchestrator
→ Role Planner AI
→ Tool Executor
→ Evidence Check
→ Answer AI
→ Action Builder
→ Flutter Action Runtime
```

## PR 목록
1. `PR_A_ROUTE_FLOW_SKELETON.md`
   - Route AI가 처리 흐름만 고르게 구조 분리
2. `PR_B_INFORMATION_TOOL_LOOP.md`
   - 정보성 질문에만 최소 1회, 최대 2회 도구 호출 구조 추가
3. `PR_C_COMMUNITY_WRITE_SIMPLE_FLOW.md`
   - 커뮤니티 게시물 작성 의도는 작성 화면만 열도록 단순화
4. `PR_D_COMMUNITY_READ_EVIDENCE.md`
   - 제목 없는 커뮤니티 게시글 구조에 맞춘 검색/빈 결과 답변 정리
5. `PR_E_FLUTTER_STREAM_ACTION_RUNTIME.md`
   - Flutter 스트림 꼬임, action 실행, 플로팅 버튼, 지도 상태 반영 정리
6. `PR_F_FESTIVAL_DB_TOOL_TRANSITION.md`
   - 축제 챗봇 도구를 DB 조회 기준으로 전환 준비

## 우선순위
권장 순서:

```text
PR_E → PR_C → PR_D → PR_A → PR_B → PR_F
```

이유:
- 지금 사용자 눈에 가장 심각한 문제는 답변 섞임과 화면 action 미반영입니다.
- 그 다음은 작성 화면 연결과 커뮤니티 읽기 품질입니다.
- Route/Flow 구조 개편은 큰 변경이므로 화면 안정화 뒤 진행합니다.

