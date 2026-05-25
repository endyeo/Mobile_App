# PR-A Route/Flow 구조 분리

## 문제
현재 planner가 `domain`, `task`, `tool`, `action` 성격의 결정을 한 번에 수행합니다. 이 때문에 간단한 지도 이동, 정보 제공, 커뮤니티 글 작성, 미지원 요청이 같은 실행 경로에서 섞이고, planner 실수가 곧 잘못된 tool/action 실행으로 이어집니다.

## 목표
Route AI는 tool/action을 고르지 않고 **처리 흐름만 선택**합니다. 서버는 flow별 실행기로 넘겨 각 도메인의 책임을 분리합니다.

## Flow 목록
```text
simple_chat
flower_information
community_read
community_write
festival_information
map_action
app_navigation
unsupported
clarification_needed
```

## 구현 계획
- `ChatbotService` 내부에 flow 단위 실행 경로를 분리합니다.
- 기존 `AgentPlan`은 바로 제거하지 않고 adapter로 유지합니다.
- Route AI prompt는 flow 선택에 집중하도록 축소합니다.
- flow 선택 결과에는 최소한 `flow`, `keyword`, `reason`, `confidence`만 포함합니다.
- 기존 smoke 평가가 깨지지 않도록 응답의 `agentRun.route`, `actions`, `toolResults` 구조는 유지합니다.

## 테스트
- `안녕` → `simple_chat`, tool/action 없음
- `장미 키우는 법 알려줘` → `flower_information`
- `수국 후기 찾아줘` → `community_read`
- `커뮤니티에 글 올릴래` → `community_write`
- `벚꽃 지도에서 보여줘` → `map_action`
- `상점에서 아이템 사줘` → `unsupported`

## 완료 기준
- Route AI가 tool/action 이름을 직접 선택하지 않습니다.
- 기존 smoke의 route/tool/action 판정이 크게 후퇴하지 않습니다.
- flow별 실행기로 코드를 나눌 수 있는 구조가 생깁니다.

