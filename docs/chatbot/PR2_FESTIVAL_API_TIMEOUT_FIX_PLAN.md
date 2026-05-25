# PR 2 축제 API Timeout 방어 계획

- 작성일: 2026-05-22
- PR 목적: Tour API 지연으로 챗봇 응답이 60초 timeout 되는 문제 해결
- 기준 실패: 배포 서버 `festival-smoke` F001/F002 및 `map-smoke` M013 timeout

## Summary

이 PR은 축제 도구의 외부 API 대기 문제만 다룬다. 커뮤니티 최신/인기 500, Route AI 구조 변경, Flutter 지도 동작 개선은 포함하지 않는다.

현재 `FestivalToolService`는 Tour API 호출에 timeout이 없고, fallback에서 여러 키워드를 순차 호출할 수 있다. 목표는 Tour API가 느리거나 실패해도 챗봇 API가 빠르게 200 응답을 반환하게 만드는 것이다.

## Key Changes

- 축제 조회용 HTTP client에 timeout을 적용한다.
  - connect timeout: 2초
  - read timeout: 3초
- fallback 키워드 순회는 P0 안정화 기준으로 최대 2개만 허용한다.
  - `꽃`
  - `벚꽃`
- 1차 `searchFestival2`가 timeout이면 fallback도 제한 시간 안에서만 시도한다.
- 축제 도구 전체 실패 시에도 `/chatbot/message`는 HTTP 200을 반환한다.
- `ToolResult.data`에 진단값을 추가한다.
  - `apiTimedOut`
  - `fallbackLimited`
  - `attemptedEndpoints`
  - `elapsedMs`

## Implementation Notes

- `FestivalToolService` 전용 `RestTemplate` 또는 timeout 설정이 적용된 HTTP client를 사용한다.
- timeout 예외는 `ToolResult ERROR`로 변환한다.
- API 실패 시 축제명을 생성하지 않는다.
- 지도 요청이 함께 있어도 축제 정보 조회 실패를 먼저 안내하고, 지도 action은 가능한 범위에서만 유지한다.

## Test Plan

로컬:

```powershell
cd C:\HAR_FLOWER
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set festival-smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set map-smoke -ShowDetails
```

배포 서버:

```powershell
cd C:\HAR_FLOWER
.\scripts\chatbot-evaluate.ps1 -BaseUrl https://ourt.kro.kr -Set festival-smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl https://ourt.kro.kr -Set map-smoke -ShowDetails
```

필수 확인 케이스:

- `F001 이번 주 꽃 축제 알려줘`
- `F002 서울 근처 꽃 축제 있어?`
- `F005 꽃 축제 지도에서 보여줘`
- `M013 축제 지도에서 보여줘`

통과 기준:

- 축제 관련 HTTP 500 0건
- 60초 timeout 0건
- 축제 API 실패 시에도 reply가 비어 있지 않음
- 축제 실패/빈 결과 응답은 10초 이내 반환

## Assumptions

- 축제 정보는 계속 Tour API 실시간 조회를 사용한다.
- 캐시 테이블, 스케줄러, 별도 축제 DB는 이번 PR에 포함하지 않는다.
- Tour API 품질 문제는 챗봇이 제어할 수 없으므로 빠른 실패와 명확한 안내를 우선한다.
