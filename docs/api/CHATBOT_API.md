# 챗봇 API 명세
<!-- 2026-05-15 automation: REPORT/records와 실제 코드 기준으로 SSE 스트림 엔드포인트, 이벤트 계약, flower_book 조회 데이터 필드를 반영함. -->
<!-- 반영: 2026-05-21 13:24 - 길찾기 액션, 축제 도구 결과, 새 꽃 정보 도구명, planner domain/task 개념 반영 -->

- 문서 버전: v1.4.0
- 최종 반영일: 2026-05-21

## 1. 공통

Base path:

```text
/chatbot
```

챗봇 컨트롤러는 `com.flower.backend.common.dto.ApiResponse`를 사용한다.

성공 응답 공통 형식:

```json
{
  "success": true,
  "data": {}
}
```

일반 예외는 `GlobalExceptionHandler`를 통해 `com.flower.backend.common.response.ApiResponse` 형식으로 반환될 수 있다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INTERNAL_SERVER_ERROR",
    "message": "서버 오류가 발생했습니다."
  }
}
```

## 2. POST `/chatbot/message`

사용자 메시지를 받아 챗봇 응답, 도구 실행 결과, 앱 제어 액션을 반환한다.

### Request

```json
{
  "message": "근처 벚꽃 지도에서 보여줘",
  "session_id": "session-123",
  "context": {
    "lat": 37.5665,
    "lng": 126.978
  }
}
```

필드:

- `message`: 필수, blank 불가
- `session_id`: 선택, 없으면 서버가 UUID 생성
- `context.lat`: 선택, 사용자 현재 위도
- `context.lng`: 선택, 사용자 현재 경도

### Response

```json
{
  "success": true,
  "data": {
    "reply": "벚꽃 위치를 지도에서 확인할 수 있도록 준비했습니다.",
    "action": {
      "type": "NAVIGATE",
      "target": "MAP",
      "params": {}
    },
    "actions": [
      {
        "type": "NAVIGATE",
        "target": "MAP",
        "params": {}
      },
      {
        "type": "MAP_SET_SEARCH_QUERY",
        "target": "MAP",
        "params": {
          "query": "벚꽃"
        }
      }
    ],
    "agentRun": {
      "mode": "SPRING_AI_ROUTER_PLANNED_LIGHTWEIGHT_AGENTIC_RAG",
      "route": "MAP_FLOWER",
      "specialist": "RouterAgent",
      "steps": [
        {
          "step": 1,
          "agent": "RouterAgent",
          "tool": "routeAndPlan",
          "status": "SUCCESS",
          "message": "AIPlanner selected the required search tools and internal client follow-ups."
        }
      ]
    },
    "toolResults": [
      {
        "tool": "flower.searchFlowerSpots",
        "status": "SUCCESS",
        "summary": "'벚꽃' flower spot search returned 1 result(s).",
        "data": {
          "items": []
        }
      }
    ],
    "sessionId": "session-123"
  }
}
```

### Response fields

- `reply`: 사용자에게 보여줄 챗봇 답변
- `action`: 기존 Flutter UI 호환용 대표 액션, `actions`의 첫 번째 항목
- `actions`: Flutter가 실행할 액션 목록
- `agentRun`: 라우팅/도구 실행 trace
- `toolResults`: 도구 실행 결과
- `sessionId`: 서버가 사용한 최종 session id

## 3. DELETE `/chatbot/session/{sessionId}`

지정한 챗봇 세션을 서버 메모리에서 삭제한다.

### Request

Path variable:

- `sessionId`: 삭제할 세션 ID

### Response

```json
{
  "success": true,
  "data": null
}
```

## 4. POST `/chatbot/message/stream`

플로팅 챗봇이 사용하는 SSE 엔드포인트다. HTTP 응답 본문 전체를 한 번에 반환하지 않고, 진행 상태와 최종 답변을 이벤트로 순차 전송한다.

### Request

요청 바디는 `POST /chatbot/message`와 동일하다.

```json
{
  "message": "벚꽃 어떻게 키워?",
  "session_id": "session-123",
  "context": {
    "lat": 37.5665,
    "lng": 126.978
  }
}
```

### Event sequence

구현상 일반적인 순서는 아래와 같다.

```text
CONNECTED
STATUS
CONTEXT_PLANNED
STATUS
ACTION (optional)
TOOL_RESULT (0..n)
STATUS
FINAL_ANSWER
DONE
```

에러가 발생하면 `ERROR` 다음 `DONE(reason=error)`를 보낸다.

### Event payload examples

`CONNECTED`

```json
{
  "sessionId": "session-123"
}
```

`STATUS`

```json
{
  "stage": "SEARCH",
  "message": "꽃 정보를 검색하고 있어요."
}
```

`ACTION`

```json
{
  "action": {
    "type": "NAVIGATE",
    "target": "MAP",
    "params": {}
  },
  "actions": [
    {
      "type": "NAVIGATE",
      "target": "MAP",
      "params": {}
    }
  ]
}
```

`TOOL_RESULT`

```json
{
  "toolResult": {
    "tool": "flower.lookupDescriptionSource",
    "status": "SUCCESS",
    "summary": "'벚꽃' flower description lookup returned 1 result(s).",
    "data": {
      "items": [
        {
          "flowerBookId": 1,
          "dataNo": "data-1",
          "name": "벚꽃",
          "scientificName": "Prunus serrulata",
          "description": "설명",
          "source": "NONGSARO"
        }
      ],
      "queryExpanded": false
    }
  }
}
```

`FINAL_ANSWER`

`POST /chatbot/message`의 `data` 필드와 동일한 `ChatMessageResponse` JSON을 그대로 전송한다.

## 5. ChatAction

```json
{
  "type": "NAVIGATE",
  "target": "FLOWER_BOOK",
  "params": {}
}
```

허용 값:

| type | target | params | 설명 |
| --- | --- | --- | --- |
| `NAVIGATE` | `MAP` | `{}` | 지도 화면 이동 |
| `NAVIGATE` | `COMMUNITY` | optional `{ "query": "..." }` | 커뮤니티 화면 이동 |
| `NAVIGATE` | `COMMUNITY_COMPOSE` | `null` | 커뮤니티 글 작성 화면 이동 |
| `NAVIGATE` | `WALK` | `{}` | 산책/만보기 화면 이동 |
| `NAVIGATE` | `FLOWER_BOOK` | optional `{ "flowerId": 1 }` | 도감 화면 이동 |
| `NAVIGATE` | `SAVED` | `{}` | 저장 화면 이동 |
| `MAP_SET_SEARCH_QUERY` | `MAP` | `{ "query": "벚꽃" }` | 지도 검색어 적용 |
| `MAP_SHOW_FLOWER` | `MAP` | `{ "flowerId": 1 }` | 지도에서 꽃 위치 강조 |
| `MAP_OPEN_FLOWER_PREVIEW` | `MAP` | `{ "flowerId": 1 }` | 지도에서 꽃 미리보기 열기 |
| `MAP_OPEN_ROUTE_CHOOSER` | `MAP` | `{ "flowerId": 1 }` | 지도에서 길찾기 이동수단 선택 열기 | <!-- 반영: 2026-05-21 13:24 -->
| `MAP_START_ROUTE` | `MAP` | `{ "flowerId": 1, "routeMode": "transit" }` | 지도에서 길찾기 실행 | <!-- 반영: 2026-05-21 13:24 -->

## 6. AgentRunTrace

```json
{
  "mode": "SPRING_AI_ROUTER_PLANNED_LIGHTWEIGHT_AGENTIC_RAG",
  "route": "MAP_FLOWER",
  "specialist": "RouterAgent",
  "steps": [
    {
      "step": 1,
      "agent": "RouterAgent",
      "tool": "routeAndPlan",
      "status": "SUCCESS",
      "message": "..."
    }
  ]
}
```

## 7. ToolResult

```json
{
  "tool": "community.searchPosts",
  "status": "SUCCESS",
  "summary": "'all' community search returned 5 result(s).",
  "data": {
    "items": []
  },
  "error": null
}
```

`status`는 현재 구현상 `SUCCESS`, `ERROR`, `READY` 등이 trace/tool 맥락에 따라 사용된다.

꽃 도감 조회 도구의 `data`는 `items` 외에 아래 보조 필드를 가질 수 있다.

- `queryExpanded`: 사용자가 꽃 이름을 특정하지 않아 후보 키워드 확장을 사용했는지 여부
- `candidateKeywords`: 후보 확장에 사용된 꽃 이름 목록

월/계절 추천 도구 `flower.recommendByMonth` (`flower.recommendSeasonalFlowers`는 호환 wrapper)은 `data.month`, `data.items`, `data.source`를 반환한다. 각 item은 꽃 도감 id, 꽃 이름, 개화일, 꽃말, 출처, 승인 명소 수와 대표 명소 정보를 포함할 수 있다. <!-- 반영: 2026-05-21 13:24 -->

축제 도구 `festival.searchFlowerFestivals`는 `data.items`, `data.source`, `data.dateFilter`, `data.rangeStart`, `data.rangeEnd`, `data.today`, `data.primaryEndpoint`, `data.fallbackEndpoint`, `data.keywordFallbackUsed`, `data.rawFestivalCount`, `data.flowerFilteredCount` 등 진단값을 반환한다. 각 item은 `contentId`, `title`, `address`, `period`, `eventStartDate`, `eventEndDate`, `imageUrl`, `lat`, `lng`, `source`, `distanceKm`를 포함할 수 있다. <!-- 반영: 2026-05-21 13:24 -->

상점, 구매, 퀘스트, 미션, 인증, 포인트 지급처럼 v1에서 직접 실행하지 않는 요청은 `app.unsupported` ToolResult로 반환되며 앱 액션은 비어 있다.

축제 예매/예약/결제 요청도 `app.unsupported`로 반환된다. <!-- 반영: 2026-05-21 13:24 -->
