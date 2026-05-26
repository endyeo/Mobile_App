# AI 기능 안정화 분리 PR 계획

## Summary
현재 AI 기능의 핵심 문제는 답변 품질 하나가 아니라 SSE 실행 생명주기, 세션 동시성, Flutter 플로팅 챗봇 상태, 앱 액션 실행 타이밍, 테스트 공백이 함께 얽힌 상태입니다. 한 PR에서 모두 고치면 원인과 회귀를 분리하기 어렵기 때문에, 사용자 체감 오류를 먼저 줄이고 서버 안정성을 뒤따라 보강하는 순서로 PR을 나눕니다.

## 권장 PR 순서
1. `PR1_FLUTTER_STREAM_PARSER_AND_ERROR_STATE.md`
   - Flutter SSE 파싱 실패와 사용자 오류 표시를 먼저 안정화
2. `PR2_FLOATING_CHAT_STATE_OWNER.md`
   - 화면별 플로팅 버튼 인스턴스가 공유 상태를 어긋나게 다루는 문제 정리
3. `PR3_ACTION_EXECUTION_TIMING.md`
   - ACTION 이벤트 즉시 화면 이동으로 최종 답변이 사라지거나 늦게 보이는 문제 정리
4. `PR4_BACKEND_SSE_EXECUTOR_AND_TIMEOUT.md`
   - 백엔드 SSE 작업 executor, 타임아웃, 연결 종료 처리를 분리
5. `PR5_SESSION_HISTORY_CONCURRENCY.md`
   - 서버 세션 히스토리 동시성 및 요청 순서 안정화
6. `PR6_CONTRACT_AND_TEST_COVERAGE.md`
   - 액션 계약, 문서, Flutter/백엔드 회귀 테스트 보강

## 공통 원칙
- 담당 범위는 AI 챗봇과 앱 제어 연결부로 제한합니다.
- 지도, 커뮤니티, 산책/포인트 내부 구현은 직접 고치지 않고 챗봇 액션 전달부만 다룹니다.
- 사용자에게 내부 route, tool, action, exception detail을 노출하지 않습니다.
- 쓰기 작업, 구매, 예약, 관리자성 요청은 기존처럼 자동 실행하지 않습니다.
- 각 PR은 자체 검증 가능한 최소 단위로 유지합니다.

## 완료 기준
- 플로팅 챗봇에서 연속 질문, 중지, 화면 이동, 네트워크 오류가 서로 섞이지 않습니다.
- SSE 연결이 실패해도 앱이 깨지지 않고 사용자가 이해 가능한 메시지를 봅니다.
- 같은 세션의 동시 요청에서 히스토리 순서와 응답 매칭이 안전합니다.
- 액션 계약과 실제 Flutter 처리 가능 범위가 문서와 테스트에 반영됩니다.

