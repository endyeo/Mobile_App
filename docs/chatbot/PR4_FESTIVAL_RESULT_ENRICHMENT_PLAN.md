# PR 4 축제 날짜 보강 필수화 및 실제 결과 반환 계획

- 작성일: 2026-05-22
- PR 목적: 챗봇이 축제를 안내하기 전에 행사 날짜를 반드시 확보하도록 Tour API 상세 보강을 추가
- 기준 관찰: 로컬 `festival-smoke` 12/12 PASS지만 `items=0`, `excludedUnknownDateCount=39`

## Summary

이 PR은 축제 API timeout 방어가 아니라, **챗봇이 축제 날짜를 확인한 뒤에만 축제를 안내하도록 만드는 품질 개선 PR**이다. PR2가 Tour API 지연/timeout 방어라면, PR4는 Tour API 후보 데이터의 부족한 날짜 정보를 `detailIntro2`로 보강해 기간 필터와 지난 축제 제외가 실제로 동작하게 만든다.

현재 로컬 결과는 `searchKeyword2` fallback으로 축제 후보 39개를 가져오지만, 후보 전부가 `eventStartDate/eventEndDate`를 갖지 않아 `excludedUnknownDateCount=39`로 제외된다. 따라서 챗봇은 “조회된 축제 데이터에서 안내드릴 일정을 찾지 못했습니다”만 답한다.

## Root Cause

현재 흐름:

```text
searchFestival2
-> 결과 없음
-> searchKeyword2 fallback: 꽃, 벚꽃
-> 꽃 키워드 필터 통과
-> 날짜 없음
-> hasUsableDate=false
-> 전부 excludedUnknownDateCount로 제외
-> items=[]
```

문제는 `searchKeyword2`가 축제명/좌표 후보는 주지만, 행사 시작일/종료일이 비어 있는 경우가 많다는 점이다. 챗봇은 날짜를 모르는 축제를 추천하면 안 되므로, fallback 후보는 `detailIntro2`로 날짜를 보강한 뒤에만 최종 결과로 사용할 수 있다.

해결 방향:

```text
searchKeyword2 후보
-> contentId/contentTypeId=15 기준으로 detailIntro2 호출
-> eventStartDate/eventEndDate 보강
-> 지난 축제 제외
-> 요청 기간과 겹치는 축제만 반환
```

## Key Changes

- 축제 챗봇 결과 정책을 고정한다.
  - 시작일과 종료일이 모두 확인된 축제만 사용자에게 안내한다.
  - 날짜 없는 축제는 추천 목록에 넣지 않는다.
  - 날짜 없는 축제를 “다가오는 축제”로 추정하지 않는다.
  - `eventEndDate < today`인 축제는 제외한다.
- `FestivalToolService`에 축제 상세 보강 단계를 추가한다.
  - 대상: 날짜가 없는 `FestivalItem`
  - 사용 API: Tour API `detailIntro2`
  - 필수 파라미터: `contentId`, `contentTypeId=15`, `MobileOS=ETC`, `MobileApp=FlowerApp`, `_type=json`
- `detailIntro2` 응답에서 날짜 필드를 읽어 기존 후보에 병합한다.
  - `eventstartdate`
  - `eventenddate`
- 보강은 timeout 정책 안에서만 수행한다.
  - PR2의 전체 시간 예산을 유지한다.
  - 상세 보강은 최대 5개 후보까지만 수행한다.
  - 시간이 부족하면 보강을 중단하고 진단값에 표시한다.
- `ToolResult.data`에 진단값을 추가한다.
  - `detailIntroAttemptedCount`
  - `detailIntroEnrichedCount`
  - `detailIntroFailedCount`
  - `detailIntroLimited`
  - `excludedUnknownDateCount`
- 날짜 보강 후에도 날짜가 없으면 추천 목록에는 넣지 않는다.
- 답변에는 확인된 기간을 반드시 포함한다.
  - 예: `기간: 2026.06.01 - 2026.06.10`
  - 기간이 확인되지 않은 축제는 답변 후보에서 제외한다.

## Implementation Notes

- `FestivalItem`은 불변 record이므로 날짜 보강용 메서드를 추가한다.
  - 예: `withDates(String eventStartDate, String eventEndDate)`
- `fetchPriorityKeywordFestivals(...)` 이후 또는 `selectFestivals(...)` 직전에 날짜 없는 후보를 보강한다.
- 보강 순서는 필터 효율을 위해 아래처럼 한다.
  1. dedupe
  2. 위치 있음 확인
  3. 꽃 축제 제목 필터
  4. 날짜 없는 후보만 `detailIntro2` 보강
  5. 지난 축제 제외
  6. 요청 기간 필터
  7. 정렬/최대 5개 반환
- `detailIntro2`가 실패하거나 timeout이면 해당 후보는 날짜 불명으로 남기고 추천 목록에서 제외한다.
- `searchFestival2` 결과에 이미 날짜가 있으면 `detailIntro2`를 생략한다.
- `searchKeyword2` fallback 결과는 날짜가 없을 수 있으므로 날짜 없는 후보에 한해 `detailIntro2`를 호출한다.
- `detailIntro2`에서도 `eventstartdate`, `eventenddate`가 모두 있어야 최종 후보로 사용한다.
  - 시작일만 있으면 제외한다.
  - 종료일만 있으면 제외한다.
  - 둘 중 하나라도 없으면 기간을 추정하지 않고 제외한다.
- API 키가 없거나 Tour API 실패 시 축제명을 생성하지 않는 기존 정책은 유지한다.

## Test Plan

로컬 서버에서 Tour API 키가 적용된 상태로 테스트한다.

```powershell
cd C:\HAR_FLOWER
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set festival-smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set map-smoke -ShowDetails
```

필수 확인 케이스:

- `이번 주 꽃 축제 알려줘`
- `서울 근처 꽃 축제 있어?`
- `벚꽃 축제 찾아줘`
- `수국 축제 알려줘`
- `다가오는 꽃 축제 알려줘`
- `축제 지도에서 보여줘`

통과 기준:

- HTTP 500 0건
- 60초 timeout 0건
- `detailIntroAttemptedCount`가 1 이상이어야 한다.
- `detailIntroEnrichedCount`가 1 이상이면 날짜 보강 성공으로 본다.
- `excludedUnknownDateCount`가 기존 39건 수준에서 감소해야 한다.
- 실제로 진행 중이거나 예정된 꽃 축제가 Tour API에 있으면 `items.length > 0`이어야 한다.
- 현재 날짜 기준 진행/예정 꽃 축제가 정말 없으면 `items=0`은 허용한다.
- `items`에 포함된 모든 축제는 `eventStartDate`와 `eventEndDate`를 모두 가져야 한다.
- 최종 reply에는 축제명이 나온 경우 기간도 함께 나와야 한다.

## Assumptions

- 축제 전용 DB 테이블은 만들지 않는다.
- Tour API 실시간 조회 흐름은 유지한다.
- `locationBasedList2`는 축제 정보가 아니라 관광지/꽃길용이므로 이번 PR에 포함하지 않는다.
- 상세 보강은 API 호출 수를 늘리므로 최대 5개로 제한한다.
- PR2의 timeout 방어가 먼저 적용된 상태를 전제로 한다.
- 날짜 없는 축제를 사용자에게 노출하지 않는 것을 서비스 품질 기준으로 삼는다.
