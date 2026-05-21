# MapAgent 명세
<!-- 반영: 2026-05-21 13:24 - MAP_OPEN_ROUTE_CHOOSER/MAP_START_ROUTE 길찾기 액션, MAP_OPEN_FLOWER_PREVIEW 대표 꽃 자동 전달, focusFlowerById 지도 JS, /api/v1/map/routes 엔드포인트 반영 -->

- 문서 버전: v1.1.0
- 최종 반영일: 2026-05-21

## 1. 책임

MapAgent는 챗봇이 지도 화면을 열거나 지도 화면에 검색/꽃 위치 표시 요청을 전달할 수 있도록 앱 액션을 준비한다. 지도 렌더링, Kakao Map 내부 구현, 위치 데이터의 실제 표시 로직은 지도 기능의 책임이며 이 문서 범위가 아니다.

구현 위치:

- Backend: `flower-backend/src/main/java/com/flower/backend/chatbot/tool/MapAgent/MapNavigationTools.java`
- Flutter 실행: `flower_app/lib/app_actions/app_action_runtime.dart`
- 지도 화면: `flower_app/lib/screens/kakao_map_screen.dart`

## 2. 제공 도구

MapAgent의 각 도구는 하나의 지도 관련 기능만 수행한다. 지도 화면 열기, 검색어 적용, 꽃 위치 강조, 꽃 미리보기 열기는 서로 합치지 않고 별도 도구와 별도 액션으로 유지한다.

도구 식별자와 `@Tool(description)`은 AI가 직접 읽는 값이므로 영어만 사용한다. 개발자가 읽는 한국어 설명은 코드 주석의 `KO:` 라인과 이 명세 문서에 남긴다.

### `openMapScreen()`

- 목적: 지도 화면 열기
- AI 설명: `Prepare an internal client follow-up that opens the map screen.`
- 한국어 설명: 지도 화면을 여는 앱 내부 액션을 준비한다.
- 입력: 없음
- 출력 액션:

```json
{
  "type": "NAVIGATE",
  "target": "MAP",
  "params": {}
}
```

### `setMapSearchQuery(query)`

- 목적: 지도 화면 검색어 적용
- AI 설명: `Prepare an internal client follow-up that applies a search query to the map screen.`
- 한국어 설명: 지도 화면 검색창에 검색어를 적용하는 앱 내부 액션을 준비한다.
- 입력: `query`
- 입력 한국어 설명: 지도 검색창에 넣을 검색어
- 정규화: null/blank이면 빈 문자열, 최대 80자
- 출력 액션:

```json
{
  "type": "MAP_SET_SEARCH_QUERY",
  "target": "MAP",
  "params": {
    "query": "벚꽃"
  }
}
```

### `showFlowerOnMap(flowerId)`

- 목적: 특정 꽃 위치를 지도에서 강조
- AI 설명: `Prepare an internal client follow-up that highlights a flower location on the map.`
- 한국어 설명: 지도에서 특정 꽃 위치를 강조하는 앱 내부 액션을 준비한다.
- 입력: `flowerId`
- 입력 한국어 설명: 지도에서 강조할 꽃 ID
- 출력 액션:

```json
{
  "type": "MAP_SHOW_FLOWER",
  "target": "MAP",
  "params": {
    "flowerId": 1
  }
}
```

### `openFlowerMapPreview(flowerId)`

- 목적: 지도 화면에서 특정 꽃 미리보기 열기
- AI 설명: `Prepare an internal client follow-up that opens a flower preview in the map screen.`
- 한국어 설명: 지도 화면에서 특정 꽃 미리보기를 여는 앱 내부 액션을 준비한다.
- 입력: `flowerId`
- 입력 한국어 설명: 지도 화면에서 미리보기로 열 꽃 ID
- 출력 액션:

```json
{
  "type": "MAP_OPEN_FLOWER_PREVIEW",
  "target": "MAP",
  "params": {
    "flowerId": 1
  }
}
```

### `openRouteChooser(flowerId)` <!-- 반영: 2026-05-21 13:24 -->

- 목적: 지도 화면에서 길찾기 이동수단 선택 열기
- 한국어 설명: 꽃 장소를 목적지로 하는 길찾기 이동수단 선택 패널을 여는 앱 내부 액션을 준비한다.
- 입력: `flowerId`
- 출력 액션:

```json
{
  "type": "MAP_OPEN_ROUTE_CHOOSER",
  "target": "MAP",
  "params": {
    "flowerId": 1
  }
}
```

### `startRoute(flowerId, routeMode)` <!-- 반영: 2026-05-21 13:24 -->

- 목적: 지도 화면에서 길찾기 실행
- 한국어 설명: 꽃 장소를 목적지로 하는 길찾기를 실행하는 앱 내부 액션을 준비한다.
- 입력: `flowerId`, `routeMode` (`walk`, `car`, `transit`)
- 출력 액션:

```json
{
  "type": "MAP_START_ROUTE",
  "target": "MAP",
  "params": {
    "flowerId": 1,
    "routeMode": "transit"
  }
}
```

## 3. 공통 실행 규칙

- `ChatActionValidator`는 `MAP` intent가 있으면 `NAVIGATE MAP`을 우선 추가한다.
- `MAP`과 `FLOWER` intent가 함께 있고 keyword가 있으면 `MAP_SET_SEARCH_QUERY`를 자동 보강할 수 있다.
- 꽃 검색 결과가 있고 아직 꽃 표시 액션이 없으면 대표 꽃의 `flowerId`로 `MAP_SHOW_FLOWER`가 추가될 수 있고, `MAP_OPEN_FLOWER_PREVIEW`로 대표 꽃 장소 미리보기를 자동 전달한다. <!-- 반영: 2026-05-21 13:24 -->
- `route_request`가 true이면 꽃 검색 결과가 있을 때 `MAP_OPEN_ROUTE_CHOOSER` 또는 `MAP_START_ROUTE`를 생성한다. `route_mode`에 따라 이동수단이 결정된다. <!-- 반영: 2026-05-21 13:24 -->
- Flutter는 지도 관련 액션이 하나라도 있으면 다른 화면 액션보다 우선해 `KakaoMapScreen`으로 이동한다.
- 지도 JS의 `FlowerMap.focusFlowerById`는 꽃 데이터 로딩 후 지도 중심 이동과 정보 패널 표시를 수행한다. pending focus 처리로 데이터 로딩 전 요청도 지원한다. <!-- 반영: 2026-05-21 13:24 -->

## 4. 실패 및 제한 사항

- `MAP_SHOW_FLOWER`, `MAP_OPEN_FLOWER_PREVIEW`는 숫자형 또는 숫자 문자열 `flowerId`만 validator를 통과한다.
- `MAP_OPEN_ROUTE_CHOOSER`, `MAP_START_ROUTE`는 `MAP` target에 숫자형 `flowerId`만 validator를 통과한다. <!-- 반영: 2026-05-21 13:24 -->
- 지도 화면이 전달받은 액션을 어떻게 해석하는지는 지도 화면 구현 책임이다.
- 사용자 위치 context는 요청 DTO에 있으며 `nearby` 플래그가 있을 때 꽃 명소 검색에서 거리 정렬에 사용된다. <!-- 반영: 2026-05-21 13:24 -->
- 도보/자동차 길찾기 액션은 작동하지만 백엔드 route API는 v1에서 `transit`만 실제 경로 조회를 지원한다. <!-- 반영: 2026-05-21 13:24 -->
- `/api/v1/map/routes` 엔드포인트가 추가되었으며 `TransitRouteController`에서 `POST /api/v1/map/routes`와 `POST /api/v1/map/transit-route`를 제공한다. <!-- 반영: 2026-05-21 13:24 -->
