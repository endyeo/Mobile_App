# AI 챗봇 공통 명세
<!-- 2026-05-15 automation: REPORT/records와 실제 코드 기준으로 SSE 스트림, 음성 입력, GENERAL/fallback 라우팅, flower_book 조회 규칙을 반영함. -->
<!-- 반영: 2026-05-21 13:24 - planner domain/task 구조, 축제 도메인, 길찾기 액션, 꽃 정보 도구 확장, 로컬 보정 제거, ChatFloatingButton 위치 전달 반영 -->
<!-- 반영: 2026-05-22 automation - ChatbotService 프롬프트 계층 분리와 docs/chatbot 프롬프트 명세 연결을 현재 코드 기준으로 반영함. -->

- 문서 버전: v1.4.1
- 최종 반영일: 2026-05-22

## 1. 목적

AI 챗봇은 사용자의 자연어 요청을 분석해 꽃 데이터, 커뮤니티 데이터, 지도 화면 이동 등 앱 내부 기능과 연결한다. 이 문서는 챗봇의 공통 흐름과 API 응답 계약만 정의한다. 지도/꽃/커뮤니티 도구의 세부 동작은 각 도구 명세서에서 다룬다.

도구별 문서:

- `tools/MAP_AGENT_SPEC.md`
- `tools/FLOWER_AGENT_SPEC.md`
- `tools/COMMUNITY_AGENT_SPEC.md`
- `tools/FESTIVAL_AGENT_SPEC.md` <!-- 반영: 2026-05-21 13:24 -->

프롬프트 문서:

- `docs/chatbot/CHATBOT_PROMPT_SPEC.md` - planner/repair/answer/fallback 프롬프트 구조와 계약 <!-- 반영: 2026-05-22 automation -->
- `docs/chatbot/CHATBOT_PROMPT_KO_SPEC.md` - 현재 코드 프롬프트의 한국어 검토용 해설 <!-- 반영: 2026-05-22 automation -->

## 도구 설계 원칙

챗봇 도구는 하나의 도구가 하나의 기능만 수행하는 것을 원칙으로 한다.

- 도구 하나는 검색, 화면 이동, 검색어 적용, 미리보기 열기, 작성 화면 열기처럼 명확히 분리된 단일 기능만 담당한다.
- 여러 기능이 필요한 사용자 요청은 개별 도구를 합치지 않고 Router/Service 계층에서 여러 도구 호출 또는 여러 `ChatAction`으로 조합한다.
- 도구 내부에서 다른 도메인의 부수 효과를 함께 수행하지 않는다. 예를 들어 꽃 검색 도구는 지도 이동을 직접 수행하지 않고, 지도 이동은 MapAgent 액션으로 분리한다.
- 실제 저장, 게시, 구매, 포인트 지급처럼 사용자 데이터나 외부 상태를 바꾸는 작업은 별도 승인된 기능/API가 없는 한 챗봇 도구에서 실행하지 않는다.

## 2. 주요 구성

백엔드 구성:

- `ChatbotController`: `/chatbot/message`, `/chatbot/message/stream`, `/chatbot/session/{sessionId}` 제공
- `ChatbotService`: 세션 관리, planner `domain/task` 기반 라우팅, 도구 실행, Spring AI 응답 생성. 내부 프롬프트는 `planningSystemPrompt()`, `planningRepairSystemPrompt()`, `buildAnswerSystemPrompt()`로 나뉘고, answer prompt는 다시 base/format/domain style 조합으로 생성된다. <!-- 반영: 2026-05-22 automation -->
- `ChatActionValidator`: planner가 만든 액션만 허용 목록 기준으로 정규화하고 중복을 제거
- `RouteIntent`: `GENERAL`, `MAP`, `FLOWER`, `FLOWER_GROW`, `COMMUNITY`, `WALK`, `SAVED`, `QUEST`, `SHOP`, `FESTIVAL` <!-- 반영: 2026-05-21 13:24 -->
- `ChatbotActionContext`: request scope 도구 실행 상태와 액션 저장
- `FestivalToolService`: Tour API 기반 축제 검색 도구 <!-- 반영: 2026-05-21 13:24 -->

Flutter 구성:

- `ChatbotService`: `/chatbot/message` 단건 호출과 `/chatbot/message/stream` SSE 호출, 이벤트 파싱
- `ChatAction`: 앱 액션 모델
- `AppActionRuntime`: 액션 목록을 실제 화면 이동으로 실행
- `ChatFloatingButton`: 실제 플로팅 챗봇 UI, SSE 진행 상태/도구 결과/최종 답변 처리, 가능한 경우 Geolocator로 현재 위치를 context에 전달 <!-- 반영: 2026-05-21 13:24 -->
- `MainActivity`: `flower_app/speech` MethodChannel로 Android 음성 인식과 권한 확인/요청 처리
- `MainScreen`, `ChatScreen`: 챗봇 요청 진입점

## 3. 대화 처리 흐름

1. Flutter는 일반 호출에서는 `/chatbot/message`, 플로팅 UI에서는 `/chatbot/message/stream`으로 사용자 메시지, `session_id`, 선택 위치 context를 전송한다.
2. 서버가 `session_id`가 없으면 UUID를 생성한다.
3. 스트림 시작 시 서버는 `CONNECTED` 이벤트와 한국어 상태 메시지를 먼저 전송한다.
4. `ChatbotService`가 Spring AI 기반 AI planner를 호출해 `domain`, `task`, `keyword`, `needs_screen`, `date_filter`, `nearby`, `route_request`, `route_mode` 등을 결정한다. <!-- 반영: 2026-05-21 13:24 -->
5. planner 출력 `domain`은 `flower_info`, `festival_info`, `community`, `map_place`, `app_navigation`, `unsupported`, `general` 중 하나다. 서버가 이를 기존 `RouteIntent`, 정보 도구, 앱 action으로 변환한다. <!-- 반영: 2026-05-21 13:24 -->
6. planner JSON이 계약을 어기면 한 번만 repair prompt로 재요청한다. OpenAI API key가 없거나 planner가 실패하면 fallback으로 `GENERAL` intent와 빈 `actions`만 반환한다. <!-- 반영: 2026-05-21 13:24 -->
7. 서버는 planner 결과에 로컬 intent/action을 강제로 추가하지 않는다. 이전의 `reinforcePlanWithLocalSignals` 로컬 보정은 제거되었다. <!-- 반영: 2026-05-21 13:24 -->
8. `ChatActionValidator`는 planner가 제안한 액션만 허용 목록 기준으로 정규화하고, 중복 `NAVIGATE MAP`을 제거하며, `MAP_SET_SEARCH_QUERY`는 `MAP+FLOWER+keyword`일 때만 유지한다.
9. `flower_info` domain에서는 `task`에 따라 `flower.getBasicInfo`, `flower.getMeaningAndBloom`, `flower.getGrowGuide`, `flower.recommendByMonth`, `flower.inferCandidates` 중 하나를 선택한다. <!-- 반영: 2026-05-21 13:24 -->
10. `community` domain에서는 `task`(`search_posts`, `open_community`, `open_composer`)에 따라 커뮤니티 검색 도구 또는 화면 이동 액션을 실행한다. `unsupported/community_mutation`은 `app.unsupported`만 반환한다. <!-- 반영: 2026-05-21 13:24 -->
11. `festival_info` domain에서는 `festival.searchFlowerFestivals` 도구를 실행한다. <!-- 반영: 2026-05-21 13:24 -->
12. `map_place` domain에서는 꽃 명소 검색 후 지도 액션을 만들고, `route_request`가 true이면 길찾기 액션(`MAP_OPEN_ROUTE_CHOOSER` 또는 `MAP_START_ROUTE`)을 추가한다. <!-- 반영: 2026-05-21 13:24 -->
13. 서버는 `STATUS`, `ACTION`, `TOOL_RESULT`, `FINAL_ANSWER`, `DONE` 이벤트를 순차 전송한다.
11. Flutter는 `ACTION` 수신 시 즉시 `AppActionRuntime.execute()`를 실행하고, `FINAL_ANSWER` 수신 후 마지막 말풍선을 최종 답변으로 교체한다.
12. 사용자가 정지 버튼을 누르면 Flutter가 SSE 요청을 취소하고 진행 중 말풍선을 제거하며 최종 답변을 표시하지 않는다.

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

현재 AI planner는 `domain/task` 기반 계약을 사용하며, 서버가 이를 아래 `RouteIntent`로 변환한다. <!-- 반영: 2026-05-21 13:24 -->

planner `domain` 값:

- `flower_info`: 꽃 기본 정보, 꽃말/개화, 재배 가이드, 월별 추천, 후보 추정 <!-- 반영: 2026-05-21 13:24 -->
- `festival_info`: 축제 검색, 근처 축제 추천, 축제 지도 열기 <!-- 반영: 2026-05-21 13:24 -->
- `community`: 게시글 검색, 커뮤니티 열기, 글 작성 화면, 변경 요청 차단 <!-- 반영: 2026-05-21 13:24 -->
- `map_place`: 장소 검색, 길찾기 <!-- 반영: 2026-05-21 13:24 -->
- `app_navigation`: 지도/도감/산책/저장 화면 이동 <!-- 반영: 2026-05-21 13:24 -->
- `unsupported`: 지원하지 않는 요청 (커뮤니티 변경, 구매, 예매 등) <!-- 반영: 2026-05-21 13:24 -->
- `general`: 인사, 잡담, 일반 질문 <!-- 반영: 2026-05-21 13:24 -->

공통 `RouteIntent`는 다음과 같다.

- `MAP`: 지도, 위치, 근처, 길찾기, 주변 요청
- `FLOWER`: 꽃, 개화, 꽃명, 추천, 명소, 도감 요청
- `COMMUNITY`: 커뮤니티, 게시글, 후기, 글 요청
- `FLOWER_GROW`: 꽃 키우기, 재배, 관리, 물주기, 햇빛, 토양 요청
- `FESTIVAL`: 축제, 행사, 꽃 축제 요청 <!-- 반영: 2026-05-21 13:24 -->
- `WALK`: 산책, 만보기, 걸음, 포인트 화면 요청
- `SAVED`: 저장됨, 북마크 화면 요청
- `QUEST`: 퀘스트, 미션, 인증 요청. v1에서는 실행하지 않고 미지원 응답만 반환
- `SHOP`: 상점, 구매, 상품, 아이템 요청. v1에서는 실행하지 않고 미지원 응답만 반환
- `GENERAL`: 인사, 감사, 잡담, 일반 질문처럼 앱 화면 이동이나 검색 도구 실행이 필요 없는 요청

현재 Flutter 실행 연결은 `MAP`, `COMMUNITY`, `WALK`, `FLOWER_BOOK`, `SAVED` 중심이다. `QUEST`, `SHOP`은 v1에서 앱 액션으로 허용하지 않는다.

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

- `NAVIGATE`: `MAP`, `COMMUNITY`, `COMMUNITY_COMPOSE`, `WALK`, `FLOWER_BOOK`, `SAVED`
- `MAP_SET_SEARCH_QUERY`: `target=MAP`, `params.query`
- `MAP_SHOW_FLOWER`: `target=MAP`, `params.flowerId`
- `MAP_OPEN_FLOWER_PREVIEW`: `target=MAP`, `params.flowerId`
- `MAP_OPEN_ROUTE_CHOOSER`: `target=MAP`, `params.flowerId` — 길찾기 이동수단 선택 열기 <!-- 반영: 2026-05-21 13:24 -->
- `MAP_START_ROUTE`: `target=MAP`, `params.flowerId`, `params.routeMode` — 길찾기 실행 <!-- 반영: 2026-05-21 13:24 -->

Flutter 실행 규칙:

- 지도 액션이 하나라도 있으면 `KakaoMapScreen(initialActions: mapActions)`로 이동한다.
- 지도 액션이 없으면 첫 번째 화면 액션만 실행한다.
- `COMMUNITY`는 `CommunityFeedScreen`으로 이동한다.
- `COMMUNITY_COMPOSE`는 `CreatePostScreen`으로 이동한다.
- `WALK`/`PEDOMETER`는 `PedometerScreen`으로 이동한다.
- `FLOWER`, `FLOWER_BOOK`, `BOOK`은 `FlowerBookPage`로 이동한다.
- `SAVED`, `BOOKMARK`, `BOOKMARKS`는 `SavedPage`로 이동한다.
- 매핑되지 않은 target은 스낵바로 준비 중 메시지를 표시한다.

## 7. 응답 생성 정책

Spring AI 응답은 도구 결과만 사실 근거로 사용해야 한다. 정확한 개화일, 위치, 게시글 내용, 구매/글 작성 완료 여부를 임의로 만들어내지 않는다.

커뮤니티 글 작성 요청은 `NAVIGATE COMMUNITY_COMPOSE`로 작성 화면 이동까지만 가능하며 실제 게시글 저장이나 초안 생성은 하지 않는다.

상점, 구매, 퀘스트, 미션, 인증, 포인트 지급은 v1 챗봇 도구가 직접 실행하지 않는다.

한국어 질문에는 최종 설명, 주의사항, 출처 언급을 자연스러운 한국어로 작성해야 한다. 도구 내부 영어 라벨(`description`, `growTips`, `source`, `Tool results`)은 최종 답변에 그대로 노출하지 않는다.

꽃 이름을 모르는 묘사형 질문은 꽃 도감 조회에서만 후보 키워드 확장을 허용한다. 이 경우 답변은 추정 기반 검색임을 밝혀야 하며, 축제/행사/명소/지도 추천 문맥에서는 후보 확장을 사용하지 않는다.

OpenAI API key가 없거나 Spring AI 호출이 실패하면 로컬 도구 결과 기반 fallback 응답을 반환한다.

플로팅 챗봇의 Android 음성 입력은 `hasRecordAudioPermission -> requestRecordAudioPermission -> listen` 순서로 동작한다. 권한이 없으면 마이크 권한 안내 스낵바를 표시하고, 챗봇 내역/입력창이 열린 상태의 시스템 뒤로가기는 현재 화면 이탈 대신 챗봇 UI만 닫는다.

## 8. 실패 및 제한 사항

- 챗봇 세션은 메모리 기반이라 서버 재시작 시 유지되지 않는다.
- `ChatbotActionContext`를 사용하는 도구 클래스가 있으나 현재 `ChatbotService`의 주요 실행 경로는 직접 도구 서비스를 호출하고 액션을 조립한다.
- 위치 context는 `ChatFloatingButton`에서 Geolocator로 취득해 요청 DTO에 전달하며, `nearby` 플래그가 있을 때 꽃 명소 검색과 축제 검색에서 거리 정렬에 사용된다. <!-- 반영: 2026-05-21 13:24 -->
- 도보/자동차 길찾기는 v1에서 액션은 받을 수 있지만 백엔드 route API는 대중교통만 실제 지원한다. <!-- 반영: 2026-05-21 13:24 -->
- 챗봇 외 기능의 내부 구현 변경은 AGENTS.md 권한 밖이다.
