# MapAgent 명세

## 1. 책임

MapAgent는 챗봇이 지도 화면을 열거나 지도 화면에 검색/꽃 위치 표시 요청을 전달할 수 있도록 앱 액션을 준비한다. 지도 렌더링, Kakao Map 내부 구현, 위치 데이터의 실제 표시 로직은 지도 기능의 책임이며 이 문서 범위가 아니다.

구현 위치:

- Backend: `flower-backend/src/main/java/com/flower/backend/chatbot/tool/MapAgent/MapNavigationTools.java`
- Flutter 실행: `flower_app/lib/app_actions/app_action_runtime.dart`
- 지도 화면: `flower_app/lib/screens/kakao_map_screen.dart`

## 2. 제공 도구

### `openMapScreen()`

- 목적: 지도 화면 열기
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
- 입력: `query`
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
- 입력: `flowerId`
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
- 입력: `flowerId`
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

## 3. 공통 실행 규칙

- `ChatActionValidator`는 `MAP` intent가 있으면 `NAVIGATE MAP`을 우선 추가한다.
- `MAP`과 `FLOWER` intent가 함께 있고 keyword가 있으면 `MAP_SET_SEARCH_QUERY`를 자동 보강할 수 있다.
- 꽃 검색 결과가 있고 아직 꽃 표시 액션이 없으면 대표 꽃의 `flowerId`로 `MAP_SHOW_FLOWER`가 추가될 수 있다.
- Flutter는 지도 관련 액션이 하나라도 있으면 다른 화면 액션보다 우선해 `KakaoMapScreen`으로 이동한다.

## 4. 실패 및 제한 사항

- `MAP_SHOW_FLOWER`, `MAP_OPEN_FLOWER_PREVIEW`는 숫자형 또는 숫자 문자열 `flowerId`만 validator를 통과한다.
- 지도 화면이 전달받은 액션을 어떻게 해석하는지는 지도 화면 구현 책임이다.
- 사용자 위치 context는 요청 DTO에 있으나 현재 MapAgent 명세상 거리 기반 정렬/검색 정책은 정의되어 있지 않다.
