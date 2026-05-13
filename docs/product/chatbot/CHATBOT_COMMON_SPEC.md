# AI 챗봇 공통 명세

## 1. 목적

AI 챗봇은 사용자의 자연어 요청을 분석해 꽃 데이터, 커뮤니티 데이터, 지도 화면 이동 등 앱 내부 기능과 연결한다. 이 문서는 챗봇의 공통 흐름과 API 응답 계약만 정의한다. 지도/꽃/커뮤니티 도구의 세부 동작은 각 도구 명세서에서 다룬다.

도구별 문서:

- `tools/MAP_AGENT_SPEC.md`
- `tools/FLOWER_AGENT_SPEC.md`
- `tools/COMMUNITY_AGENT_SPEC.md`

## 도구 설계 원칙

챗봇 도구는 하나의 도구가 하나의 기능만 수행하는 것을 원칙으로 한다.

- 도구 하나는 검색, 화면 이동, 검색어 적용, 미리보기 열기, 초안 준비처럼 명확히 분리된 단일 기능만 담당한다.
- 여러 기능이 필요한 사용자 요청은 개별 도구를 합치지 않고 Router/Service 계층에서 여러 도구 호출 또는 여러 `ChatAction`으로 조합한다.
- 도구 내부에서 다른 도메인의 부수 효과를 함께 수행하지 않는다. 예를 들어 꽃 검색 도구는 지도 이동을 직접 수행하지 않고, 지도 이동은 MapAgent 액션으로 분리한다.
- 실제 저장, 게시, 구매, 포인트 지급처럼 사용자 데이터나 외부 상태를 바꾸는 작업은 별도 승인된 기능/API가 없는 한 챗봇 도구에서 실행하지 않는다.

## 2. 주요 구성

백엔드 구성:

- `ChatbotController`: `/chatbot/message`, `/chatbot/session/{sessionId}` 제공
- `ChatbotService`: 세션 관리, 라우팅, 도구 실행, Spring AI 응답 생성
- `ChatActionValidator`: 앱 액션 정규화와 허용 목록 검증
- `RouteIntent`: `MAP`, `FLOWER`, `COMMUNITY`, `WALK`, `QUEST`, `SHOP`
- `ChatbotActionContext`: request scope 도구 실행 상태와 액션 저장

Flutter 구성:

- `ChatbotService`: `/chatbot/message` 호출, 응답 파싱
- `ChatAction`: 앱 액션 모델
- `AppActionRuntime`: 액션 목록을 실제 화면 이동으로 실행
- `MainScreen`, `ChatScreen`: 챗봇 요청 진입점

## 3. 대화 처리 흐름

1. Flutter가 사용자 메시지, `session_id`, 선택 위치 context를 `/chatbot/message`로 전송한다.
2. 서버가 `session_id`가 없으면 UUID를 생성한다.
3. `ChatbotService`가 Spring AI 플래너를 시도한다.
4. OpenAI API key 또는 ChatClient가 없거나 플래너가 실패하면 로컬 fallback planner를 사용한다.
5. 플래너 결과와 로컬 keyword/intent 감지를 병합한다.
6. `ChatActionValidator`가 액션을 허용 목록 기준으로 보정한다.
7. intent에 따라 꽃/커뮤니티 도구 결과를 수집한다.
8. Spring AI가 도구 결과와 앱 액션 정보를 기반으로 사용자 응답 문장을 생성한다.
9. Spring AI 호출이 실패하거나 API key가 없으면 로컬 fallback 응답을 반환한다.
10. 서버가 `reply`, `action`, `actions`, `agentRun`, `toolResults`, `sessionId`를 반환한다.
11. Flutter가 `actions`를 `AppActionRuntime.execute()`로 전달해 화면 이동 또는 지도 액션을 실행한다.

## 4. 세션 정책

- 세션 키: 요청의 `session_id`
- 미전달 시 서버가 UUID 생성
- 저장 위치: 서버 메모리 `ConcurrentHashMap`
- 히스토리 최대 길이: 12개 message turn
- TTL: 1시간
- 만료 세션 정리: 5분마다 scheduled cleanup
- 세션 삭제: `DELETE /chatbot/session/{sessionId}`

세션은 서버 메모리 기반이므로 서버 재시작 시 사라진다.

## 5. 라우팅 의도

공통 intent는 다음과 같다.

- `MAP`: 지도, 위치, 근처, 길찾기, 주변 요청
- `FLOWER`: 꽃, 개화, 꽃명, 추천, 명소, 도감 요청
- `COMMUNITY`: 커뮤니티, 게시글, 후기, 글 요청
- `WALK`: 산책, 만보기, 걸음, 포인트 요청
- `QUEST`: 퀘스트, 미션, 인증 요청
- `SHOP`: 상점, 구매, 상품, 아이템 요청

현재 Flutter 실행 연결은 `MAP`, `COMMUNITY`, `WALK`, `FLOWER_BOOK`, `SAVED` 중심이다. `QUEST`, `SHOP`은 백엔드 validator에서 허용하지만 Flutter `AppActionRuntime`에는 전용 화면 매핑이 없다.

## 6. 앱 액션 계약

공통 액션 DTO:

```json
{
  "type": "NAVIGATE",
  "target": "MAP",
  "params": {}
}
```

필드 의미:

- `type`: 액션 종류
- `target`: 대상 화면 또는 기능 영역
- `params`: 화면에 전달할 추가 파라미터

허용 액션:

- `NAVIGATE`: `MAP`, `COMMUNITY`, `WALK`, `FLOWER_BOOK`, `SAVED`, `QUEST`, `SHOP`
- `MAP_SET_SEARCH_QUERY`: `target=MAP`, `params.query`
- `MAP_SHOW_FLOWER`: `target=MAP`, `params.flowerId`
- `MAP_OPEN_FLOWER_PREVIEW`: `target=MAP`, `params.flowerId`
- `PREPARE_DRAFT`: `target=COMMUNITY`

Flutter 실행 규칙:

- 지도 액션이 하나라도 있으면 `KakaoMapScreen(initialActions: mapActions)`로 이동한다.
- 지도 액션이 없으면 첫 번째 화면 액션만 실행한다.
- `COMMUNITY`는 `CommunityFeedScreen`으로 이동한다.
- `WALK`/`PEDOMETER`는 `PedometerScreen`으로 이동한다.
- `FLOWER`, `FLOWER_BOOK`, `BOOK`은 `FlowerBookPage`로 이동한다.
- `SAVED`, `BOOKMARK`, `BOOKMARKS`는 `SavedPage`로 이동한다.
- 매핑되지 않은 target은 스낵바로 준비 중 메시지를 표시한다.

## 7. 응답 생성 정책

Spring AI 응답은 도구 결과만 사실 근거로 사용해야 한다. 정확한 개화일, 위치, 게시글 내용, 구매/글 작성 완료 여부를 임의로 만들어내지 않는다.

커뮤니티 글 작성 요청은 `PREPARE_DRAFT`까지만 가능하며 실제 게시글 저장은 하지 않는다. 응답에서는 초안 준비 상태임을 명확히 해야 한다.

OpenAI API key가 없거나 Spring AI 호출이 실패하면 로컬 도구 결과 기반 fallback 응답을 반환한다.

## 8. 실패 및 제한 사항

- 챗봇 세션은 메모리 기반이라 서버 재시작 시 유지되지 않는다.
- `ChatbotActionContext`를 사용하는 도구 클래스가 있으나 현재 `ChatbotService`의 주요 실행 경로는 직접 도구 서비스를 호출하고 액션을 조립한다.
- 위치 context는 요청 DTO에 존재하지만 현재 공통 라우팅 흐름에서 거리 계산에는 사용되지 않는다.
- 챗봇 외 기능의 내부 구현 변경은 AGENTS.md 권한 밖이다.
