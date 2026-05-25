# PR-F 축제 DB 도구 전환 준비

## 문제
축제 정보는 앞으로 DB에 저장하기로 결정했습니다. 따라서 챗봇 도구가 Tour API를 직접 여러 페이지 조회하고 `searchFestival2:1,2,3`, `rawSamples`, `pageSamples` 같은 진단값을 응답에 포함하는 구조는 서비스용으로 맞지 않습니다.

## 목표
챗봇 축제 도구를 DB 조회 기준으로 전환할 수 있게 계약을 정리합니다.

## 목표 흐름
```text
축제 질문
→ Route AI: festival_information
→ FestivalPlanner
→ Festival DB Tool
→ Evidence Check
→ Answer AI
→ Flutter
```

## DB 조회 규칙
- 시작일 필수
- 종료일 필수
- 종료일이 오늘 이전이면 제외
- 기간 없는 축제 제외
- 장소 없는 축제는 지도 action 제외
- 지도 요청이 있으면 축제명 또는 대표 장소를 검색어로 사용

## ToolResult 정리
챗봇 ToolResult에는 서비스에 필요한 값만 남깁니다.

```text
source=festival_db
items
dateFilter
query
excludedPastCount
locationUsed
```

제거 대상:
```text
searchFestival2:1
searchFestival2:2
searchFestival2:3
attemptedEndpoints
rawSamples
pageSamples
detailIntroAttemptedCount
keywordFallbackUsed
```

## 테스트
- `다가오는 꽃 축제 알려줘`
- `이번 달 꽃 축제 알려줘`
- `꽃 축제 지도에서 보여줘`
- `지난 꽃 축제 알려줘`

기대:
- 기간 있는 축제만 답변
- 지난 축제 추천 없음
- DB 결과 없음과 오류 구분
- API 페이지 진단값이 챗봇 응답에 없음

## 완료 기준
- 챗봇 축제 응답은 DB 조회 결과만 사용합니다.
- Tour API 수집/동기화 로그와 챗봇 응답 진단값이 분리됩니다.
- 축제명, 기간, 장소가 있는 항목만 사용자에게 안내됩니다.

