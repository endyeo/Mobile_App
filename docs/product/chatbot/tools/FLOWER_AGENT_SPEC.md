# FlowerAgent 명세

## 1. 책임

FlowerAgent는 승인된 꽃 위치/꽃 도감 데이터를 검색하고, 필요한 경우 도감 화면 이동 액션을 준비한다. 지도 표시 자체는 MapAgent가 담당한다.

구현 위치:

- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/FlowerAgent/FlowerTools.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/FlowerAgent/FlowerToolService.java`
- 데이터 저장소: `FlowerRepository`

## 2. 제공 도구

### `searchFlowerSpots(query)`

- 목적: 승인된 꽃 위치 데이터 검색
- 검색 대상: 꽃 이름, 품종, 주소, 설명
- 입력: `query`, blank이면 대표 승인 꽃 위치 조회
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

### `openFlowerBook()`

- 목적: 꽃 도감 화면 열기
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
- 입력: `flowerId`
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
- 꽃 위치/명소/근처/지도 요청과 함께 꽃 keyword가 있으면 FlowerAgent 검색 결과가 MapAgent 액션과 함께 사용될 수 있다.
- 검색 keyword가 없으면 전체/대표 승인 꽃 데이터를 최대 5개까지 조회한다.

## 4. 응답 원칙

- 답변은 검색된 승인 꽃 데이터만 사실 근거로 사용한다.
- 검색 결과가 없으면 결과 없음으로 응답한다.
- 정확한 개화일, 위치, 설명을 임의로 생성하지 않는다.
- 도감 화면 이동은 앱 액션으로만 전달하며, 도감 내부 구현은 수정하지 않는다.

## 5. 실패 및 제한 사항

- 검색 실패 예외를 별도로 세분화하지 않고 상위 챗봇 fallback/예외 흐름에 맡긴다.
- `openFlowerDetail`의 `flowerId`는 Flutter의 현재 `AppActionRuntime`에서 별도 상세 선택 로직으로 처리되지 않고 `FlowerBookPage` 이동에 사용된다.
- 지도 강조가 필요한 경우 FlowerAgent가 직접 지도 액션을 만들지 않고 공통 라우팅/MapAgent 규칙이 담당한다.
