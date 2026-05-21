# FestivalAgent 명세
<!-- 반영: 2026-05-21 13:24 - 축제 정보 제공 챗봇 1단계, 축제 기간 필터링, Tour API 정합성 개선 반영 -->

- 문서 버전: v1.0.0
- 최종 반영일: 2026-05-21

## 1. 책임

FestivalAgent는 Tour API를 실시간 조회해 꽃 관련 축제 정보를 검색하고, 필요한 경우 축제 지도 화면 이동 액션을 준비한다. 축제 전용 DB 테이블은 사용하지 않고, Tour API 결과를 실시간 필터링한다.

구현 위치:

- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/FestivalAgent/FestivalToolService.java`
- 데이터 소스: Tour API (`searchFestival2`, `searchKeyword2` fallback)
- API 키 설정: `@Value("${tour.api-key:${TOUR_API_KEY:}}")`

## 2. 제공 도구

### `festival.searchFlowerFestivals(keyword, location, nearby, dateFilter)`

- 목적: 꽃 관련 축제 실시간 검색
- 한국어 설명: Tour API를 조회해 꽃 관련 축제 정보를 검색하고, 기간/위치/키워드로 필터링한다.
- 입력:
  - `keyword`: 검색어 (기본값 "꽃")
  - `location`: 사용자 위치 context (lat/lng)
  - `nearby`: 근처 정렬 여부
  - `dateFilter`: 기간 필터 (`today`, `this_week`, `this_month`, `upcoming`, `none`)
- 결과 제한: 최대 5개
- 조회 순서:
  1. `searchFestival2` 엔드포인트를 1차 호출한다. `contentTypeId` 파라미터는 사용하지 않는다 (searchFestival2가 거부).
  2. 꽃축제 후보가 없으면 `searchKeyword2` 엔드포인트로 6개 우선 키워드 (`꽃`, `벚꽃`, `매화`, `유채`, `장미`, `국화`) fallback 호출을 수행한다.
- 필터링:
  - 꽃 관련 키워드가 제목에 포함된 축제만 통과
  - 이미 종료된 축제 제외
  - `dateFilter`에 따른 기간 범위 필터
  - 위치 정보가 없는 축제 제외
  - 시작일 오름차순 정렬 (nearby이면 거리순 정렬)
- 출력: `ToolResult`

`ToolResult.data` 필드:

- `items`: 축제 목록
- `source`: `"Tour API"`
- `keyword`
- `nearby`
- `dateFilter`
- `rangeStart`
- `rangeEnd`
- `today`
- `primaryEndpoint`: `"searchFestival2"`
- `fallbackEndpoint`: `"searchKeyword2"`
- `keywordFallbackUsed`
- `rawFestivalCount`
- `flowerFilteredCount`
- `excludedPastCount`
- `excludedDateCount`
- `excludedUnknownDateCount`

`items` 항목 필드:

- `contentId`
- `title`
- `name` (title과 동일)
- `address`
- `period`
- `eventStartDate`
- `eventEndDate`
- `tel`
- `imageUrl` (http → https 자동 변환)
- `lat`
- `lng`
- `source`
- `distanceKm` (위치 정보가 있을 때)

## 3. 기간 필터

planner가 `date_filter`를 제공하면 서버가 아래 기간 범위를 계산한다:

| date_filter | 범위 | 라벨 |
| --- | --- | --- |
| `today` | 오늘 하루 | 오늘 |
| `this_week` | 이번 주 월~일 | 이번 주 |
| `this_month` | 이번 달 1일~말일 | 이번 달 |
| `upcoming` (기본값) | 오늘 이후 전체 | 다가오는 일정 |

## 4. 라우팅 규칙

- 축제/행사 관련 요청은 planner에서 `festival_info` domain으로 분류된다.
- `festival_info/search_festivals`: 축제 검색 도구 실행
- `festival_info/recommend_nearby`: 근처 축제 추천 (nearby=true)
- `festival_info/open_festival_map`: 기존 `NAVIGATE MAP`과 `MAP_SET_SEARCH_QUERY`로 지도 이동
- 축제 예매/예약/결제 요청은 `unsupported/private_or_admin`으로 분류되어 `app.unsupported`만 반환한다.

## 5. 응답 원칙

- 답변은 Tour API 조회 결과만 사실 근거로 사용한다.
- 검색 결과가 없으면 결과 없음으로 응답한다.
- 축제 기간, 위치, 연락처를 임의로 생성하지 않는다.
- 장소 결과가 없을 때 임의 장소/지역 정보를 만들지 않는다.

## 6. 실패 및 제한 사항

- Tour API 키(`TOUR_API_KEY`)가 설정되지 않으면 `ERROR` 상태 ToolResult를 반환한다.
- Tour API 호출 실패 시 `ERROR` 상태 ToolResult를 반환한다.
- 특정 축제 핀 포커스 action은 v1에 포함되지 않는다.
- Tour API 응답 데이터 품질은 외부 API에 의존한다.
