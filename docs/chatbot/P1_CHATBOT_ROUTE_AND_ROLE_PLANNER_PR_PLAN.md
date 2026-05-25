# P1 챗봇 Route AI / 역할 Planner AI 분리 PR 계획

- 문서 버전: v1.0.0
- 작성일: 2026-05-22
- 대상 PR: P1 구조 개선 전용 PR
- 전제: P0 안정화 PR이 먼저 적용되어 `/chatbot/message` 500과 축제 timeout이 해결된 상태

## 1. PR 목표

현재 챗봇은 첫 Planner AI가 `domain`, `task`, `keyword`, `date_filter`, `nearby`, `route_request`, `needs_screen`까지 한 번에 결정한다. 이 구조는 기능이 늘수록 프롬프트가 길어지고, 첫 선택이 틀리면 뒤 선택도 함께 틀어진다.

P1 PR의 목표는 Planner를 두 단계로 나누는 것이다.

```text
Route AI -> 역할 Planner AI -> 서버 도구 실행 -> 답변 AI -> Flutter action 실행
```

현재 구조는 정확히는 아래에 가깝다.

```text
Planner AI -> 서버 도구 실행 -> 답변 AI -> Flutter action 실행
```

즉, 답변 AI는 이미 분리되어 있다. 이번 PR은 첫 Planner AI를 `Route AI`와 `역할 Planner AI`로 나누는 작업이다.

## 2. 설계 방향

### 2.1 Route AI

Route AI는 사용자 메시지가 어느 큰 영역인지까지만 고른다.

출력 JSON:

```json
{
  "route": "flower_info | community | festival_info | map_place | app_navigation | unsupported | general",
  "confidence": "high | medium | low",
  "reason": "짧은 한국어 이유"
}
```

Route AI가 하지 않는 것:

- 세부 task 선택
- keyword 추출
- 기간 추출
- 지도 action 판단
- 길찾기 mode 판단

Route AI 예시:

| 사용자 입력 | route |
|---|---|
| 장미가 어떤 꽃이야? | `flower_info` |
| 수국 꽃말 알려줘 | `flower_info` |
| 최신 글들은 어떤 걸 소개 해? | `community` |
| 이번 주 인기글 보여줘 | `community` |
| 축제 지도에서 보여줘 | `festival_info` |
| 벚꽃 지도에서 보여줘 | `map_place` |
| 장미 가는 길 알려줘 | `map_place` |
| 꽃 지도 열어줘 | `app_navigation` |
| 상점에서 아이템 사줘 | `unsupported` |
| 안녕 | `general` |

### 2.2 역할 Planner AI

역할 Planner AI는 Route AI가 고른 route 안에서만 세부 계획을 만든다.

공통 출력 JSON:

```json
{
  "task": "string",
  "keyword": "string",
  "date_filter": "today | this_week | this_month | month | upcoming | none",
  "month": 0,
  "year": 0,
  "nearby": false,
  "route_request": false,
  "route_mode": "walk | car | transit | none",
  "needs_screen": false,
  "confidence": "high | medium | low",
  "reason": "짧은 한국어 이유"
}
```

역할 Planner는 자기 route 밖의 task를 고를 수 없다.

예:

- `route=community`이면 `search_posts`, `latest_posts`, `popular_posts`, `open_community`, `open_composer`만 가능
- `route=flower_info`이면 `basic_info`, `meaning_bloom`, `grow_guide`, `monthly_recommendation`, `candidate_inference`만 가능
- `route=map_place`이면 `place_search`만 가능

## 3. 구현 계획

### 3.1 ChatbotService 구조 변경

현재 `createAgentPlan(...)` 흐름을 두 단계로 나눈다.

변경 후 흐름:

1. `routeUserMessage(message)` 호출
2. `RouteDecision` 반환
3. `planForRoute(message, routeDecision)` 호출
4. `RolePlanDecision` 반환
5. `AgentPlan`으로 합성
6. 기존 서버 도구 실행 로직 재사용

새 내부 record:

```java
private record RouteDecision(
        String route,
        String confidence,
        String reason,
        String source
) {}

private record RolePlanDecision(
        String task,
        String keyword,
        String dateFilter,
        int month,
        int year,
        boolean nearby,
        boolean routeRequest,
        String routeMode,
        boolean needsScreen,
        String confidence,
        String reason,
        String source
) {}
```

기존 `PlannerDecision`은 바로 제거하지 않고, 전환 기간에는 `AgentPlan` 합성용으로 유지하거나 축소한다.

### 3.2 Route AI 프롬프트

Route AI 프롬프트는 짧게 유지한다.

핵심 규칙:

- 꽃 정보 질문은 `flower_info`
- 커뮤니티 읽기/작성/화면 요청은 `community`
- 축제/행사/페스티벌은 `festival_info`
- 꽃 명소/위치/지도에서 특정 꽃 보기/길찾기는 `map_place`
- 단순 지도/도감/산책/저장 화면 열기는 `app_navigation`
- 구매/예매/자동 게시/댓글/삭제/좋아요/관리자 기능은 `unsupported`
- 인사/잡담은 `general`

Route AI는 keyword를 추출하지 않는다.

### 3.3 역할 Planner 프롬프트 분리

route별로 프롬프트를 분리한다.

- `flowerInfoPlanningPrompt()`
- `communityPlanningPrompt()`
- `festivalPlanningPrompt()`
- `mapPlacePlanningPrompt()`
- `appNavigationPlanningPrompt()`
- `unsupportedPlanningPrompt()`
- `generalPlanningPrompt()`

각 프롬프트는 자기 도메인 예시만 가진다.

효과:

- 프롬프트 길이가 줄어든다.
- 커뮤니티 최신/인기 예시가 꽃/축제/지도 예시에 묻히지 않는다.
- 꽃 개화 질문은 flower_info planner 안에서만 `meaning_bloom` vs `basic_info`를 고르면 된다.
- 지도 길찾기는 map_place planner 안에서만 판단한다.

### 3.4 검증과 repair

검증도 두 단계로 나눈다.

Route 검증:

- route 값이 허용 목록인지 확인
- 빈 값이면 `general` 또는 fallback route로 처리

Role Plan 검증:

- route별 허용 task인지 확인
- `unsupported`인데 `needs_screen=true`이면 invalid
- `general`인데 `needs_screen=true`이면 invalid
- `route_request=true`는 `map_place/place_search`에서만 허용
- `date_filter=month`이면 month 1~12 필수

Repair도 route와 role을 분리한다.

- Route repair: route 값만 고침
- Role repair: 해당 route 안에서 task/keyword/date/action 성격만 고침

### 3.5 실행 매핑 유지

서버 도구 실행부는 최대한 유지한다.

변경하지 않을 원칙:

- LLM이 tool 이름을 직접 고르지 않는다.
- 서버가 route/task를 보고 허용된 도구만 실행한다.
- action은 서버가 생성한다.
- 답변 AI는 도구 결과만 근거로 답한다.

기존 실행 매핑은 유지:

- `flower_info/basic_info` -> `flower.getBasicInfo`
- `community/latest_posts` -> `community.getLatestPosts`
- `festival_info/search_festivals` -> `festival.searchFlowerFestivals`
- `map_place/place_search` -> `flower.searchFlowerSpots`
- `unsupported/*` -> `app.unsupported`

## 4. 테스트 계획

### 4.1 로컬 smoke

```powershell
cd C:\HAR_FLOWER
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set community-smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set festival-smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set map-smoke -ShowDetails
```

### 4.2 핵심 회귀 케이스

Route AI 확인:

- `장미가 어떤 꽃이야?` -> `flower_info`
- `최신 글들은 어떤 걸 소개 해?` -> `community`
- `축제 지도에서 보여줘` -> `festival_info`
- `벚꽃 지도에서 보여줘` -> `map_place`
- `꽃 지도 열어줘` -> `app_navigation`
- `댓글 대신 달아줘` -> `unsupported`
- `안녕` -> `general`

역할 Planner 확인:

- `매화는 언제 피어?` -> `meaning_bloom`
- `동백 개화시기 알려줘` -> `meaning_bloom`
- `이번 달에 피는 꽃 추천해줘` -> `monthly_recommendation`
- `이번 주 인기글 보여줘` -> `popular_posts`, `date_filter=this_week`, `keyword=""`
- `장미 인기글 있어?` -> `popular_posts`, `keyword=장미`
- `장미 가는 길 알려줘` -> `place_search`, `route_request=true`, `route_mode=none`

### 4.3 통과 기준

- `smoke`: 13/15 이상
- `community-smoke`: 19/19
- `map-smoke`: 13개 이상
- `festival-smoke`: P0 적용 후 timeout 0건 기준으로 통과
- AIPlanner 결과 trace에는 Route AI와 Role Planner AI 단계가 모두 보여야 한다.

## 5. PR 분리 기준

이번 P1 PR에 포함할 것:

- Route AI 추가
- route별 역할 Planner AI 추가
- Route/Role validation과 repair 분리
- 기존 AgentPlan 합성 유지
- 기존 도구 실행/답변 AI/Flutter action 계약 유지

이번 P1 PR에 포함하지 않을 것:

- 커뮤니티 최신/인기 500 해결
- 축제 timeout 해결
- Flutter UI 변경
- 장기 대화 상태
- 새 도구 추가
- DB 스키마 변경

## 6. 기대 효과

- 첫 Planner가 모든 것을 한 번에 판단하는 부담을 줄인다.
- 프롬프트가 도메인별로 짧아져 유지보수가 쉬워진다.
- 커뮤니티 최신/인기, 꽃 개화, 지도 길찾기처럼 세부 판단이 섞이는 문제를 줄인다.
- 발표 시 구조를 명확히 설명할 수 있다.

최종 설명:

```text
사용자 입력
-> Route AI가 담당 도메인을 선택
-> 역할 Planner AI가 해당 도메인 안에서 세부 작업 계획 생성
-> 서버가 허용된 도구와 앱 액션만 실행
-> 답변 AI가 도구 결과를 사용자 답변으로 정리
-> Flutter가 action을 실행
```
