# P0 챗봇 서비스 안정화 PR 계획

- 문서 버전: v1.0.0
- 작성일: 2026-05-22
- 대상 PR: P0 안정화 전용 PR
- 기준 테스트: 배포 서버 `https://ourt.kro.kr` 챗봇 평가 결과

## 1. PR 목표

이번 PR은 새 기능 추가가 아니라, 배포 서버에서 바로 서비스 장애로 보이는 P0 문제만 해결한다.

P0 범위:

1. 커뮤니티 최신글/인기글 요청이 `/chatbot/message`에서 HTTP 500을 내는 문제 해결
2. 축제 질문이 Tour API 대기 때문에 60초 timeout까지 걸리는 문제 해결
3. 위 두 문제가 발생해도 챗봇 API는 500이 아니라 사용자에게 설명 가능한 200 응답을 반환하도록 보장

P1 범위는 이번 PR에서 제외한다.

- 꽃 개화 질문 도구 선택 개선
- Route AI -> 역할 Planner AI 구조 분리
- 답변 스타일 추가 개선
- 지도 내부 동작 개선

## 2. 현재 문제 요약

### 2.1 커뮤니티 최신글/인기글 500

배포 서버 테스트에서 아래 요청이 모두 HTTP 500을 반환했다.

- `최신 글들은 어떤 걸 소개 해?`
- `최근 커뮤니티 글 보여줘`
- `오늘 올라온 글 있어?`
- `인기글 알려줘`
- `이번 주 인기글 보여줘`
- `3월 인기글 보여줘`
- `장미 인기글 있어?`

관찰:

- AIPlanner 분류 전에 실패한 것이 아니라, `/chatbot/message` 응답 자체가 500이다.
- 기존 커뮤니티 검색 `community.searchPosts`는 정상이다.
- 새 도구인 `community.getLatestPosts`, `community.getPopularPosts` 경로에서만 장애가 난다.

가장 가능성 높은 원인:

- `CommunityPostRepository.findLatestPosts/findPopularPosts` JPQL이 운영 DB/엔티티 상태와 맞지 않음
- 운영 DB에 `plant_name`, `comment_count`, `like_count`, `created_at` 등 컬럼 상태가 로컬과 다름
- 도구 내부에서 예외를 `ToolResult ERROR`로 반환하기 전에 다른 위치에서 예외가 전파됨

### 2.2 축제 API timeout

배포 서버 테스트에서 아래 요청이 60초 이상 대기 후 취소됐다.

- `이번 주 꽃 축제 알려줘`
- `서울 근처 꽃 축제 있어?`
- `축제 지도에서 보여줘`

관찰:

- 일부 축제 요청은 7~10초 정도로 응답한다.
- `FestivalToolService`는 `RestTemplate` timeout 설정이 없다.
- 1차 `searchFestival2` 이후 fallback에서 여러 키워드를 순차 호출할 수 있다.

가장 가능성 높은 원인:

- Tour API 또는 서버 네트워크가 느릴 때 요청이 무제한 대기
- fallback 키워드 순차 호출이 응답 시간을 크게 늘림
- 외부 API 실패가 챗봇 전체 응답 시간을 끌고 감

## 3. 구현 계획

### 3.1 커뮤니티 최신글/인기글 500 방어

첫 번째 목표는 기능 정교화가 아니라 500 제거다.

변경 방향:

- `community.getLatestPosts`는 우선 기존 안정 경로인 `findFeed(PageRequest.of(0, 5))` 기반으로 동작하게 한다.
- `community.getPopularPosts`는 운영 DB에서 안전한 정렬만 사용한다.
  - 1순위: `likeCount DESC`
  - 2순위: `commentCount DESC`
  - 3순위: `createdAt DESC`
- 기간 필터는 500 제거 후 유지 가능하면 유지하되, 문제 원인이 되면 이번 PR에서는 degrade 처리한다.
  - 기간 쿼리 실패 시 전체 기간 최신/인기 조회로 fallback
  - fallback이 발생하면 `ToolResult.data.periodFallbackUsed=true` 추가
- 도구 내부 예외는 반드시 잡아서 `ToolResult ERROR`로 반환한다.
- `/chatbot/message` 전체가 500이 되지 않도록 `ChatbotService.executeCommunityTask(...)` 주변에서도 마지막 방어를 둔다.

응답 정책:

- DB 조회 실패 시에도 HTTP 200 유지
- 답변은 “현재 커뮤니티 글을 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.” 수준으로 짧게 안내
- 내부 SQL/JPQL/stack trace는 사용자에게 노출하지 않음

### 3.2 축제 API timeout 제한

외부 Tour API는 느리거나 실패할 수 있으므로 챗봇 응답 전체를 붙잡지 않게 한다.

변경 방향:

- 축제용 `RestTemplate`에 timeout 설정 추가
  - connect timeout: 2초
  - read timeout: 3초
- fallback 호출 제한
  - 기존 우선 키워드 전체 순회 대신 P0에서는 최대 2개만 호출: `꽃`, `벚꽃`
  - 1차 API가 timeout이면 fallback도 짧게 제한
- 축제 도구 전체가 실패해도 `/chatbot/message`는 200 유지
- `ToolResult.data`에 진단값 추가
  - `apiTimedOut`
  - `fallbackLimited`
  - `attemptedEndpoints`
  - `elapsedMs`

응답 정책:

- API timeout이면 축제명을 생성하지 않는다.
- “축제 정보를 조회하지 못했습니다. 잠시 후 다시 확인해 주세요.”라고 답한다.
- 지도 action이 있는 요청은 지도 화면은 열 수 있지만, 축제 결과가 없다는 점을 먼저 말한다.

### 3.3 서버 500 최종 방어

챗봇 도구 하나가 실패해도 전체 API가 500이 되면 서비스 품질이 무너진다.

변경 방향:

- 커뮤니티 최신/인기 도구와 축제 도구는 실패 시 `ToolResult ERROR`를 반환한다.
- `ChatbotService`는 도구 실행 중 예외를 잡아 해당 도구 실패 결과로 변환한다.
- OpenAI 답변 생성이 가능하면 실패 도구 결과를 근거로 사용자 안내를 만든다.
- OpenAI 답변 생성까지 실패하면 fallback reply로 200 응답을 만든다.

## 4. 검증 계획

### 4.1 로컬 검증

백엔드 실행:

```powershell
cd C:\HAR_FLOWER\flower-backend
$env:OPENAI_API_KEY="본인_OpenAI_API_KEY"
$env:TOUR_API_KEY="본인_TOUR_API_KEY"
.\gradlew.bat bootRun
```

평가 실행:

```powershell
cd C:\HAR_FLOWER
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set community-smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set festival-smoke -ShowDetails
```

필수 확인:

- 최신글/인기글 요청에서 HTTP 500이 0건이어야 한다.
- 축제 요청에서 60초 timeout이 0건이어야 한다.
- 축제 API 실패 시에도 `festival.searchFlowerFestivals` ToolResult가 반환되어야 한다.

### 4.2 배포 서버 검증

```powershell
cd C:\HAR_FLOWER
.\scripts\chatbot-evaluate.ps1 -BaseUrl https://ourt.kro.kr -Set community-smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl https://ourt.kro.kr -Set festival-smoke -ShowDetails
```

통과 기준:

- `community-smoke`: 19/19 PASS
- `festival-smoke`: timeout 0건
- `/chatbot/message` HTTP 500 0건
- Tour API 실패 시에도 사용자 답변이 비어 있지 않음
- 응답 시간 목표:
  - 커뮤니티 최신/인기: 5초 이내
  - 축제 API 실패/빈 결과: 10초 이내

## 5. PR 분리 기준

이번 P0 PR에 포함할 것:

- 커뮤니티 최신/인기글 500 방어
- 축제 API timeout 제한
- 도구 실패 시 챗봇 API 200 응답 보장
- 관련 smoke 평가 확인

이번 P0 PR에 포함하지 않을 것:

- Route AI -> 역할 Planner AI 구조 분리
- 꽃 개화 질문 라우팅 개선
- 장기 대화 맥락
- Flutter 화면 수정
- 커뮤니티 UI 인기글 탭
- 축제 캐시 테이블

## 6. P1 이후 방향 메모

P1은 현재 방식의 Planner Prompt 보강만으로 계속 늘리기보다 구조를 나누는 방향이 맞다.

권장 흐름:

```text
Route AI -> 역할 Planner AI -> 서버 도구 실행 -> 답변 AI -> Flutter action 실행
```

현재 구조:

```text
Planner AI -> 서버 도구 실행 -> 답변 AI -> Flutter action 실행
```

즉, 현재도 답변 AI는 따로 있다. 다만 첫 Planner AI가 route 선택과 task 선택과 keyword/date/action 성격 판단을 한 번에 맡고 있다. P1에서는 이를 `Route AI`와 `역할 Planner AI`로 나누는 것이 좋다.

분리 의도:

- Route AI: 꽃/커뮤니티/축제/지도/앱이동/미지원/일반 중 어디인지 선택
- 역할 Planner AI: 선택된 route 안에서 task, keyword, date_filter, needs_screen 등을 세밀하게 계획
- 서버: 허용된 도구와 action만 실행
- 답변 AI: 도구 결과만 보고 사용자 답변 생성

이 구조는 P0 장애 해결 후 별도 PR로 다루는 것이 안전하다.
