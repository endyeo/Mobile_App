# FestivalAgent 명세
<!-- 반영: 2026-05-21 13:24 - 축제 정보 제공 챗봇 1단계, 축제 기간 필터링, Tour API 정합성 개선 반영 -->
<!-- 반영: 2026-05-25 sync - FestivalRepository DB 우선 조회, source=festival_db, Tour API debug 필드 제거, eventStartDate/eventEndDate 필수 필터링, FESTIVAL_SOURCE_NOT_CONFIGURED, 일반 키워드 빈 쿼리 반영 -->

- 문서 버전: v1.1.0
- 최종 반영일: 2026-05-25

## 1. 책임

FestivalAgent는 꽃 관련 축제 정보를 검색하고, 필요한 경우 축제 지도 화면 이동 액션을 준비한다. `FestivalRepository`가 주입되면 DB(`festivals` 테이블)를 우선 조회하고, 없으면 Tour API로 fallback한다. <!-- 반영: 2026-05-25 sync -->

구현 위치:

- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/FestivalAgent/FestivalToolService.java`
- 데이터 소스 (DB 우선): `FestivalRepository.searchChatbotCandidates(...)` — 시작일/종료일이 모두 있고 종료일이 조회 시작일 이후인 축제만 후보로 조회 <!-- 반영: 2026-05-25 sync -->
- 데이터 소스 (fallback): Tour API (`searchFestival2`, `searchKeyword2` fallback)
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
  - 꽃 관련 키워드가 제목 또는 주소에 포함된 축제만 통과 <!-- 반영: 2026-05-25 sync - 주소도 판별 대상 -->
  - `eventStartDate`와 `eventEndDate`가 모두 있는 항목만 최종 후보로 사용 <!-- 반영: 2026-05-25 sync -->
  - 이미 종료된 축제 제외
  - `dateFilter`에 따른 기간 범위 필터
  - 위치 정보가 없는 축제 제외
  - 시작일 오름차순 정렬 (nearby이면 거리순 정렬)
- 일반 키워드(`꽃`, `축제`, `행사`, `페스티벌`)는 DB 후보를 과도하게 필터링하지 않도록 빈 키워드로 조회한다. <!-- 반영: 2026-05-25 sync -->
- 출력: `ToolResult`

`ToolResult.data` 필드: <!-- 반영: 2026-05-25 sync - DB 전환 기준으로 정리 -->

- `items`: 축제 목록
- `source`: `"festival_db"` (DB 조회 시) 또는 `"Tour API"` (fallback 시) <!-- 반영: 2026-05-25 sync -->
- `query`: 검색어
- `dateFilter`: 적용된 기간 필터
- `excludedPastCount`: 종료된 축제 제외 수 <!-- 반영: 2026-05-25 sync -->
- `locationUsed`: 위치 정보 사용 여부 <!-- 반영: 2026-05-25 sync -->

Tour API 진단용 필드(`primaryEndpoint`, `fallbackEndpoint`, `attemptedEndpoints`, `rawFestivalCount`, `flowerFilteredCount`, `excludedDateCount`, `excludedUnknownDateCount`, `pageSamples`, `rawSamples`, `elapsedMs` 등)는 챗봇 응답 데이터에 포함하지 않는다. <!-- 반영: 2026-05-25 sync -->

`items` 항목 필드:

- `contentId`
- `title`
- `name` (title과 동일)
- `address`
- `period`
- `eventStartDate` (필수 — 없으면 후보에서 제외) <!-- 반영: 2026-05-25 sync -->
- `eventEndDate` (필수 — 없으면 후보에서 제외) <!-- 반영: 2026-05-25 sync -->
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

- Tour API 키(`TOUR_API_KEY`)가 설정되지 않고 `FestivalRepository`도 없으면 `FESTIVAL_SOURCE_NOT_CONFIGURED` 에러 코드로 `ERROR` ToolResult를 반환한다. 최종 답변에 내부 코드는 노출하지 않고 guarded answer를 유지한다. <!-- 반영: 2026-05-25 sync -->
- Tour API 호출 실패 시 `ERROR` 상태 ToolResult를 반환한다. fallback timeout 후 빈 결과도 `SUCCESS`가 아니라 `ERROR`로 반환한다. <!-- 반영: 2026-05-25 sync -->
- 특정 축제 핀 포커스 action은 v1에 포함되지 않는다.
- Tour API 응답 데이터 품질은 외부 API에 의존한다.
- 축제 답변 프롬프트에 Tour API page/debug 값을 근거로 쓰지 말고, 시작일/종료일이 모두 확인된 축제만 후보로 사용하라는 규칙이 적용된다. <!-- 반영: 2026-05-25 sync -->
