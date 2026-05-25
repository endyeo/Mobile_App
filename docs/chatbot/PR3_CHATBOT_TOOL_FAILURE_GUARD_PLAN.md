# PR 3 챗봇 도구 실패 공통 방어 계획

- 작성일: 2026-05-22
- PR 목적: 특정 도구 실패가 `/chatbot/message` 전체 HTTP 500으로 번지는 문제 방지
- 기준 문제: 커뮤니티/축제 도구 실패가 API 전체 장애로 보이는 구조

## Summary

이 PR은 특정 도구 로직을 고치는 PR이 아니라, 챗봇 서버의 공통 방어선을 추가하는 PR이다. 커뮤니티 최신/인기 쿼리 수정이나 축제 timeout 정책은 각각 PR 1, PR 2에서 처리한다.

목표는 어떤 도구가 예외를 내더라도 챗봇 API가 가능한 한 HTTP 200과 사용자용 실패 안내를 반환하게 만드는 것이다.

## Key Changes

- `ChatbotService`의 도구 실행 경계에 공통 try/catch 방어를 추가한다.
- 도구 예외는 `ToolResult ERROR`로 변환한다.
- 실패한 도구 이름, task, route는 trace와 서버 로그에 남긴다.
- 최종 답변 AI에는 “도구 조회 실패” context만 전달하고 stack trace는 전달하지 않는다.
- 답변 AI 호출까지 실패하면 fallback reply로 사용자 안내를 만든다.

## Implementation Notes

- 공통 helper를 둔다.
  - 예: `safeToolResult(toolName, supplier)`
- helper는 예외 발생 시 아래 형태의 `ToolResult`를 반환한다.
  - `tool`: 실패한 도구명
  - `status`: `ERROR`
  - `summary`: 사용자에게 설명 가능한 짧은 실패 요약
  - `error`: 내부 상세가 아닌 일반 오류 문구
  - `data`: `{ "failed": true }`
- 사용자 답변에는 내부 exception class, SQL, API URL, key 정보를 노출하지 않는다.

## Test Plan

단위/로컬 확인:

- 커뮤니티 도구가 예외를 던지는 테스트 double을 넣었을 때 `/chatbot/message`가 200 응답을 만드는지 확인
- 축제 도구가 timeout 예외를 던졌을 때도 reply가 비어 있지 않은지 확인
- `toolResults[].status=ERROR`가 응답에 포함되는지 확인

수동 확인:

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/chatbot/message `
  -ContentType "application/json; charset=utf-8" `
  -Body '{"message":"인기글 알려줘","session_id":"guard-test-001"}' | ConvertTo-Json -Depth 20
```

통과 기준:

- 도구 예외로 인한 HTTP 500 0건
- 실패 답변에 내부 구현 정보 노출 0건
- 정상 도구 실행 경로는 기존과 동일하게 유지

## Assumptions

- 이 PR은 공통 안전장치이므로 PR 1, PR 2와 독립적으로 리뷰 가능해야 한다.
- 도구별 원인 해결은 별도 PR에서 한다.
- 실패를 숨기는 것이 아니라, API 전체 장애로 번지지 않게 만들고 trace/log로 원인을 남긴다.
