# 챗봇 API 명세

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

## 4. ChatAction

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
| `NAVIGATE` | `WALK` | `{}` | 산책/만보기 화면 이동 |
| `NAVIGATE` | `FLOWER_BOOK` | optional `{ "flowerId": 1 }` | 도감 화면 이동 |
| `NAVIGATE` | `SAVED` | `{}` | 저장 화면 이동 |
| `NAVIGATE` | `QUEST` | `{}` | validator 허용, Flutter 전용 매핑 없음 |
| `NAVIGATE` | `SHOP` | `{}` | validator 허용, Flutter 전용 매핑 없음 |
| `MAP_SET_SEARCH_QUERY` | `MAP` | `{ "query": "벚꽃" }` | 지도 검색어 적용 |
| `MAP_SHOW_FLOWER` | `MAP` | `{ "flowerId": 1 }` | 지도에서 꽃 위치 강조 |
| `MAP_OPEN_FLOWER_PREVIEW` | `MAP` | `{ "flowerId": 1 }` | 지도에서 꽃 미리보기 열기 |
| `PREPARE_DRAFT` | `COMMUNITY` | `{ "mode": "DRAFT_ONLY", "topic": "..." }` | 커뮤니티 초안 준비 |

## 5. AgentRunTrace

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

## 6. ToolResult

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
