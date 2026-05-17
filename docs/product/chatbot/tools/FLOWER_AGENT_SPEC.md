# FlowerAgent 명세
<!-- 2026-05-15 automation: REPORT/records와 실제 코드 기준으로 flower_book 설명/재배팁 조회, 후보 확장, projection/PageRequest 제한을 반영함. -->

- 문서 버전: v1.1.0
- 최종 반영일: 2026-05-15

## 1. 책임

FlowerAgent는 승인된 꽃 위치/꽃 도감 데이터를 검색하고, 필요한 경우 도감 화면 이동 액션을 준비한다. 지도 표시 자체는 MapAgent가 담당한다.

구현 위치:

- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/FlowerAgent/FlowerTools.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/FlowerAgent/FlowerToolService.java`
- 데이터 저장소: `FlowerRepository`, `FlowerBookRepository`

## 2. 제공 도구

FlowerAgent의 각 도구는 하나의 꽃/도감 관련 기능만 수행한다. 꽃 데이터 검색, 도감 화면 열기, 특정 꽃 상세 전달은 서로 합치지 않고 별도 도구와 별도 액션으로 유지한다.

도구 식별자와 `@Tool(description)`은 AI가 직접 읽는 값이므로 영어만 사용한다. 개발자가 읽는 한국어 설명은 코드 주석의 `KO:` 라인과 이 명세 문서에 남긴다.

### `searchFlowerSpots(query)`

- 목적: 승인된 꽃 위치 데이터 검색
- AI 설명: `Search FLOWER's approved flower spot database by flower name, species, address, or description.`
- 한국어 설명: 승인된 꽃 명소 데이터를 꽃 이름, 품종, 주소, 설명 기준으로 검색한다.
- 검색 대상: 꽃 이름, 품종, 주소, 설명
- 입력: `query`, blank이면 대표 승인 꽃 위치 조회
- 입력 한국어 설명: 꽃 명소 검색어. 비어 있으면 대표 승인 꽃 명소를 반환한다.
- 정규화: trim, 최대 100자
- 결과 제한: 최대 5개
- 출력: Spring AI 답변용 문자열 또는 `ToolResult`

`ToolResult` item 필드:

- `flowerId`
- `name`
- `species`
- `status`
- `address`
- `bloomStart`
- `bloomEnd`
- `description`
- `lat`
- `lng`

### `lookupDescriptionSource(query)`

- 목적: `flower_book` 기반 꽃 설명/출처 조회
- AI 설명: `Look up a flower's description and source from the flower_book database.`
- 한국어 설명: 꽃 이름 또는 학명으로 꽃 설명과 출처를 조회한다.
- 입력: `query`
- 입력 한국어 설명: 꽃 이름 또는 학명
- 검색 대상: `flower_book.name`, `flower_book.scientific_name`, 카테고리명
- 결과 제한: DB 레벨 최대 3건
- 조회 방식: 엔티티 전체 조회가 아니라 description/source projection만 조회
- 출력: `ToolResult`

`ToolResult.data` 필드:

- `items`
- `queryExpanded`
- `candidateKeywords`(후보 확장 시에만 포함)

`items` 항목 필드:

- `flowerBookId`
- `dataNo`
- `name`
- `scientificName`
- `description`
- `source`

### `lookupGrowTipsSource(query)`

- 목적: `flower_book` 기반 꽃 재배 팁/출처 조회
- AI 설명: `Look up a flower's grow tips and source from the flower_book database.`
- 한국어 설명: 꽃 이름 또는 학명으로 재배 팁과 출처를 조회한다.
- 입력: `query`
- 입력 한국어 설명: 꽃 이름 또는 학명
- 검색 대상: `flower_book.name`, `flower_book.scientific_name`, 카테고리명
- 결과 제한: DB 레벨 최대 3건
- 조회 방식: 엔티티 전체 조회가 아니라 growTips/source projection만 조회
- 출력: `ToolResult`

`ToolResult.data` 필드:

- `items`
- `queryExpanded`
- `candidateKeywords`(후보 확장 시에만 포함)

`items` 항목 필드:

- `flowerBookId`
- `dataNo`
- `name`
- `scientificName`
- `growTips`
- `source`

### `recommendSeasonalFlowers(month)`

- 목적: 월/계절 기준 꽃 추천
- AI 설명: `Recommend seasonal flowers for a given month using FLOWER's flower book and approved flower spots.`
- 한국어 설명: `flower_book` 월별 개화 데이터와 승인 꽃 명소를 조합해 추천한다.
- 입력: `month`, 1~12 범위 밖이면 현재 월 사용
- 결과 제한: 최대 5개
- 출력: `ToolResult`

`ToolResult.data` 필드:

- `month`
- `items`
- `source`

`items` 항목 필드:

- `flowerBookId`
- `name`
- `bloomMonth`
- `bloomDay`
- `bloomDate`
- `flowerLanguage`
- `source`
- `spotCount`
- `representativeSpotName`(승인 명소가 있을 때)
- `address`(승인 명소가 있을 때)
- `flowerId`/`lat`/`lng`(승인 명소가 있을 때)

### `openFlowerBook()`

- 목적: 꽃 도감 화면 열기
- AI 설명: `Prepare an internal client follow-up that opens FLOWER's flower book screen.`
- 한국어 설명: 꽃 도감 화면을 여는 앱 내부 액션을 준비한다.
- 입력: 없음
- 출력 액션:

```json
{
  "type": "NAVIGATE",
  "target": "FLOWER_BOOK",
  "params": {}
}
```

### `openFlowerDetail(flowerId)`

- 목적: 꽃 도감 화면에 특정 꽃 id 전달
- AI 설명: `Prepare an internal client follow-up that opens FLOWER's flower book with a selected flower id.`
- 한국어 설명: 특정 꽃 ID를 전달해 꽃 도감 화면을 여는 앱 내부 액션을 준비한다.
- 입력: `flowerId`
- 입력 한국어 설명: 꽃 도감 화면에 전달할 꽃 ID
- 출력 액션:

```json
{
  "type": "NAVIGATE",
  "target": "FLOWER_BOOK",
  "params": {
    "flowerId": 1
  }
}
```

## 3. 라우팅 규칙

- 꽃 관련 요청은 `FLOWER` intent로 분류된다.
- 사용자가 도감 열기를 요청하고 지도 요청이 없으면 `NAVIGATE FLOWER_BOOK`을 반환한다.
- 꽃 설명/특징 질문은 `lookupDescriptionSource`만 사용하고, 키우기/재배/관리 질문은 `lookupGrowTipsSource`만 사용한다.
- 월/계절 추천 질문은 `recommendSeasonalFlowers`를 사용한다.
- 꽃 위치/명소/근처/지도 요청과 함께 꽃 keyword가 있으면 `searchFlowerSpots` 결과가 MapAgent 액션과 함께 사용될 수 있다.
- 검색 keyword가 없으면 전체/대표 승인 꽃 데이터를 최대 5개까지 조회한다.
- 꽃 이름을 모르는 묘사형 질문은 후보 꽃 키워드로 확장 검색할 수 있다.
- 후보 확장은 `이름을 모르는 꽃 식별 질문`에서만 허용하고, 축제/행사/명소/장소/추천/지도 문맥에서는 수행하지 않는다.
- 서버는 하나의 꽃 정보 질문에서 설명 조회와 재배 팁 조회를 동시에 실행하지 않는다.

## 4. 응답 원칙

- 답변은 검색된 승인 꽃 데이터만 사실 근거로 사용한다.
- 검색 결과가 없으면 결과 없음으로 응답한다.
- 정확한 개화일, 위치, 설명을 임의로 생성하지 않는다.
- flower_book 설명/재배 팁 답변은 조회 결과의 `source`를 함께 사용한다.
- 후보 확장 검색이 발생한 경우 답변은 확정 식별이 아니라 후보 기반 추정임을 밝혀야 한다.
- 도감 화면 이동은 앱 액션으로만 전달하며, 도감 내부 구현은 수정하지 않는다.

## 5. 실패 및 제한 사항

- 검색 실패 예외를 별도로 세분화하지 않고 상위 챗봇 fallback/예외 흐름에 맡긴다.
- `openFlowerDetail`의 `flowerId`는 Flutter의 현재 `AppActionRuntime`에서 별도 상세 선택 로직으로 처리되지 않고 `FlowerBookPage` 이동에 사용된다.
- 지도 강조가 필요한 경우 FlowerAgent가 직접 지도 액션을 만들지 않고 공통 라우팅/MapAgent 규칙이 담당한다.
- `flower_book` 더미 데이터 추가는 현재 저장소 코드에서 확인되지 않아 이 명세 범위에 포함하지 않는다.
