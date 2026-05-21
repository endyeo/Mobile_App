# 챗봇 프롬프트 명세서

- 문서 버전: v1.0.0
- 최종 반영일: 2026-05-21
- 기준 코드: `flower-backend/src/main/java/com/flower/backend/chatbot/service/ChatbotService.java`

## 1. 목적

이 문서는 AI 꽃 도우미 챗봇에서 사용하는 프롬프트의 역할, 입력/출력 계약, 작성 원칙, 유지보수 기준을 정리한다.

현재 챗봇은 하나의 LLM이 모든 도구를 직접 자유롭게 호출하는 구조가 아니다. 먼저 Planner 프롬프트가 사용자 요청을 `domain/task` 계약으로 분류하고, 서버가 해당 계약에 맞는 도구와 앱 액션을 실행한 뒤, Answer 프롬프트가 도구 결과를 근거로 최종 답변을 만든다.

프롬프트의 실제 실행 원본은 코드이며, 이 문서는 프롬프트를 수정하거나 발표 자료에 설명할 때 기준으로 사용하는 한국어 명세다.

## 2. 프롬프트 구성

| 구분 | 코드 위치 | 역할 |
|---|---|---|
| Planner Prompt | `planningSystemPrompt()` | 사용자 문장을 `domain/task` JSON으로 분류한다. 답변을 작성하지 않는다. |
| Repair Prompt | `planningRepairSystemPrompt()` | Planner JSON이 계약을 어기면 한 번만 구조를 수정하게 한다. |
| Answer System Prompt | `buildAnswerSystemPrompt(...)` | 최종 답변 생성 규칙을 조합한다. |
| Base Answer Prompt | `buildBaseAnswerPrompt()` | 모든 답변에 적용되는 공통 안전/품질 규칙이다. |
| Answer Format Prompt | `buildAnswerFormatPrompt(...)` | 정보 있음/없음, 화면 이동, 미지원 요청의 문장 형식을 정한다. |
| Domain Style Prompt | `buildDomainStylePrompt(...)` | 꽃, 커뮤니티, 축제 등 도메인별 답변 스타일을 정한다. |
| Fallback Reply | `fallbackReply(...)` | Spring AI 호출 실패 시 로컬 도구 결과 기반으로 응답한다. |

## 3. 공통 작성 원칙

프롬프트 설명과 예시는 한국어로 작성한다. 단, 서버 계약에 쓰이는 필드명과 enum 값은 코드와 일치해야 하므로 영어를 유지한다.

- `domain`, `task`, `keyword`, `date_filter` 같은 JSON 필드명은 영어로 유지한다.
- `flower_info`, `community`, `latest_posts` 같은 enum 값은 코드 계약이므로 영어로 유지한다.
- 필드 의미, 선택 기준, 예시 설명, 답변 원칙은 한국어로 작성한다.
- 문장별 하드코딩 대신 필드 의미와 선택 기준을 명확히 한다.
- 예시는 모델이 패턴을 이해하도록 돕는 대표 사례이며, 서버 실행의 유일한 근거가 아니다.

## 4. Planner Prompt 명세

### 4.1 역할

Planner Prompt는 사용자 입력을 읽고 아래 JSON 계약만 반환한다. Planner는 사용자에게 보일 답변을 작성하지 않고, 도구 이름이나 앱 액션을 직접 선택하지 않는다.

서버는 Planner가 반환한 `domain/task` 조합을 실행 계약으로 해석한다.

```json
{
  "domain": "flower_info | festival_info | community | map_place | app_navigation | unsupported | general",
  "task": "string",
  "keyword": "optional concrete topic keyword only",
  "date_filter": "today | this_week | this_month | month | upcoming | none",
  "month": 0,
  "year": 0,
  "nearby": false,
  "route_request": false,
  "route_mode": "walk | car | transit | none",
  "needs_screen": false,
  "confidence": "high | medium | low",
  "reason": "짧은 한국어 판단 이유"
}
```

### 4.2 필드 의미

| 필드 | 의미 |
|---|---|
| `domain` | 사용자의 요청이 속한 큰 영역이다. |
| `task` | 해당 domain 안에서 수행할 구체 작업이다. |
| `keyword` | 꽃 이름, 식물명, 장소명, 명확한 검색 주제만 넣는다. 요청 방식, 정렬 기준, 기간 표현은 넣지 않는다. |
| `date_filter` | 기간 조건이다. 기간이 없으면 `none`을 사용한다. |
| `month` | `date_filter=month`일 때 월 숫자를 넣는다. |
| `year` | 특정 연도가 있으면 넣고, 없으면 서버가 현재 연도를 사용할 수 있게 0으로 둔다. |
| `nearby` | 사용자가 근처, 주변, 내 위치 기반 요청을 했는지 나타낸다. |
| `route_request` | 사용자가 길찾기 또는 가는 길을 요청했는지 나타낸다. |
| `route_mode` | 이동수단이 명시된 경우만 `walk`, `car`, `transit` 중 하나를 넣는다. 없으면 `none`이다. |
| `needs_screen` | 앱 화면을 열어야 하는 요청인지 나타낸다. |
| `confidence` | Planner 판단 신뢰도다. |
| `reason` | 사람이 진단할 수 있는 짧은 한국어 판단 이유다. |

### 4.3 Domain 목록

| domain | 의미 |
|---|---|
| `flower_info` | 꽃 기본 정보, 꽃말/개화, 재배, 월별 추천, 이름 모르는 꽃 후보 추정 |
| `festival_info` | 꽃 축제, 행사, 페스티벌 정보 조회와 지도 연결 |
| `community` | 커뮤니티 게시글 검색, 최신글, 인기글, 커뮤니티 화면, 글 작성 화면 |
| `map_place` | 꽃 명소, 위치, 주변 장소, 길찾기 |
| `app_navigation` | 단순 화면 이동 |
| `unsupported` | 구매, 예매, 자동 게시, 삭제, 좋아요, 댓글, 관리자 기능 등 직접 실행하지 않는 요청 |
| `general` | 인사, 잡담, 도구가 필요 없는 일반 대화 |

### 4.4 주요 task 목록

| domain | task | 의미 |
|---|---|---|
| `flower_info` | `basic_info` | 꽃 이름, 학명, 설명, 특징 |
| `flower_info` | `meaning_bloom` | 꽃말, 개화 시기 |
| `flower_info` | `grow_guide` | 키우는 법, 물주기, 햇빛, 토양, 관리 |
| `flower_info` | `monthly_recommendation` | 월/계절 기준 꽃 추천 |
| `flower_info` | `candidate_inference` | 색상/모양 설명으로 후보 꽃 추정 |
| `festival_info` | `search_festivals` | 꽃 축제 정보 조회 |
| `festival_info` | `recommend_nearby` | 근처 꽃 축제 추천 |
| `festival_info` | `open_festival_map` | 꽃 축제 정보를 조회하고 지도 화면 연결 |
| `community` | `search_posts` | 특정 꽃/주제의 게시글 또는 후기 검색 |
| `community` | `latest_posts` | 최신 커뮤니티 글 조회 |
| `community` | `popular_posts` | 인기 커뮤니티 글 조회 |
| `community` | `open_community` | 커뮤니티 화면 열기 |
| `community` | `open_composer` | 커뮤니티 글 작성 화면 열기 |
| `map_place` | `place_search` | 꽃 명소, 위치, 볼 수 있는 곳, 길찾기 목적지 검색 |
| `app_navigation` | `open_map` | 지도 화면 열기 |
| `app_navigation` | `open_flower_book` | 꽃 도감 화면 열기 |
| `app_navigation` | `open_walk` | 산책 화면 열기 |
| `app_navigation` | `open_saved` | 저장 화면 열기 |
| `unsupported` | `community_mutation` | 좋아요, 댓글, 삭제, 자동 저장, 자동 게시 등 커뮤니티 쓰기 요청 |
| `unsupported` | `shop_purchase` | 상점 구매 요청 |
| `unsupported` | `quest_verification` | 퀘스트/인증 요청 |
| `unsupported` | `private_or_admin` | 개인정보, 관리자 기능, 권한 없는 기능 |
| `general` | `general_chat` | 일반 대화 |

## 5. Planner 선택 규칙

### 5.1 정보 질문 우선

꽃 설명, 꽃말, 개화, 재배 질문은 화면 이동보다 정보 제공을 우선한다.

- “장미 정보 알려줘”는 `flower_info/basic_info`다.
- “장미 도감 열어줘”는 `app_navigation/open_flower_book`이다.
- “장미 정보 알려주고 도감도 열어줘”처럼 화면 요청이 명확할 때만 화면 연결을 함께 고려한다.

### 5.2 커뮤니티 읽기 요청

커뮤니티 read 요청은 검색, 최신글, 인기글을 구분한다.

- 특정 꽃/주제 후기를 찾으면 `community/search_posts`다.
- 최신, 최근, 새로 올라온 글은 `community/latest_posts`다.
- 인기글, 좋아요 많은 글, 댓글 많은 글, 많이 본 글은 `community/popular_posts`다.
- 전체 최신글/전체 인기글 요청에는 `keyword=""`를 반환한다.
- `keyword`에는 꽃 이름이나 명확한 주제만 넣는다.

예시:

| 사용자 입력 | domain | task | keyword | date_filter | needs_screen |
|---|---|---|---|---|---|
| 수국 후기 찾아줘 | `community` | `search_posts` | `수국` | `none` | `false` |
| 벚꽃 커뮤니티 글 보여줘 | `community` | `search_posts` | `벚꽃` | `none` | `true` |
| 최신 글들은 어떤 걸 소개 해? | `community` | `latest_posts` | 빈 문자열 | `none` | `false` |
| 최근 커뮤니티 글 보여줘 | `community` | `latest_posts` | 빈 문자열 | `none` | `true` |
| 오늘 올라온 글 있어? | `community` | `latest_posts` | 빈 문자열 | `today` | `false` |
| 인기글 알려줘 | `community` | `popular_posts` | 빈 문자열 | `none` | `false` |
| 이번 주 인기글 보여줘 | `community` | `popular_posts` | 빈 문자열 | `this_week` | `true` |
| 3월 인기글 보여줘 | `community` | `popular_posts` | 빈 문자열 | `month` | `true` |
| 장미 인기글 있어? | `community` | `popular_posts` | `장미` | `none` | `false` |
| 수국 최신 후기 보여줘 | `community` | `latest_posts` | `수국` | `none` | `true` |

### 5.3 커뮤니티 작성과 금지 작업

- “글 써줘”, “글 올릴래”, “작성 화면 열어줘”는 `community/open_composer`다.
- 자동 게시, 자동 저장, 좋아요, 댓글, 삭제, 수정은 `unsupported/community_mutation`이다.
- 작성 화면을 여는 것은 허용하지만 실제 게시글 저장은 하지 않는다.

예시:

| 사용자 입력 | domain | task | 실행 원칙 |
|---|---|---|---|
| 수국 후기 글 써줘 | `community` | `open_composer` | 작성 화면만 연다. |
| 커뮤니티에 글 올릴래 | `community` | `open_composer` | 작성 화면만 연다. |
| 글 내용까지 대신 저장해줘 | `unsupported` | `community_mutation` | action 없이 거절한다. |
| 이 게시글 좋아요 눌러줘 | `unsupported` | `community_mutation` | action 없이 거절한다. |
| 댓글 대신 달아줘 | `unsupported` | `community_mutation` | action 없이 거절한다. |
| 게시글 삭제해줘 | `unsupported` | `community_mutation` | action 없이 거절한다. |

### 5.4 축제 질문

축제, 행사, 페스티벌이 핵심 주제이면 `festival_info`로 분류한다. 꽃이라는 단어가 없어도 “축제 지도”, “행사 지도”는 단순 지도 열기가 아니라 축제 도구 대상이다.

예시:

| 사용자 입력 | domain | task | date_filter | needs_screen |
|---|---|---|---|---|
| 이번 주 꽃 축제 알려줘 | `festival_info` | `search_festivals` | `this_week` | `false` |
| 이번 달 갈 만한 꽃 행사 추천해줘 | `festival_info` | `search_festivals` | `this_month` | `false` |
| 다가오는 꽃 축제 알려줘 | `festival_info` | `search_festivals` | `upcoming` | `false` |
| 서울 근처 꽃 축제 있어? | `festival_info` | `recommend_nearby` | `upcoming` | `false` |
| 축제 지도에서 보여줘 | `festival_info` | `open_festival_map` | `upcoming` | `true` |
| 행사 지도 열어줘 | `festival_info` | `open_festival_map` | `upcoming` | `true` |

### 5.5 지도와 장소 질문

장소, 명소, 위치, 근처, 주변, 지도, 볼 수 있는 곳이 핵심이면 `map_place/place_search`를 사용한다. 단순히 “꽃 지도 열어줘”처럼 검색 대상이 없으면 `app_navigation/open_map`이다.

예시:

| 사용자 입력 | domain | task | keyword | nearby | needs_screen |
|---|---|---|---|---|---|
| 꽃 지도 열어줘 | `app_navigation` | `open_map` | 빈 문자열 | `false` | `true` |
| 벚꽃 지도에서 보여줘 | `map_place` | `place_search` | `벚꽃` | `false` | `true` |
| 수국 명소 어디 있어? | `map_place` | `place_search` | `수국` | `false` | `false` |
| 근처 꽃 명소 추천해줘 | `map_place` | `place_search` | `꽃` | `true` | `true` |
| 주변 꽃길 찾아줘 | `map_place` | `place_search` | `꽃길` | `true` | `true` |
| 라벤더 볼 수 있는 곳 알려줘 | `map_place` | `place_search` | `라벤더` | `false` | `false` |

### 5.6 길찾기 질문

“가는 길”, “길찾기”는 단순 지도 표시가 아니라 길찾기 의도다.

- 목적지를 먼저 특정해야 한다.
- 이동수단이 없으면 `route_mode=none`으로 두고 이동수단 선택 패널을 열게 한다.
- 사용자가 도보, 자동차, 대중교통을 명시한 경우에만 해당 mode를 반환한다.
- 장소 결과가 없으면 서버는 길찾기 action을 만들지 않는다.

예시:

| 사용자 입력 | domain | task | keyword | route_request | route_mode | needs_screen |
|---|---|---|---|---|---|---|
| 장미 가는 길 알려줘 | `map_place` | `place_search` | `장미` | `true` | `none` | `true` |
| 장미까지 도보 길찾기 | `map_place` | `place_search` | `장미` | `true` | `walk` | `true` |
| 수국 명소 자동차 길찾기 | `map_place` | `place_search` | `수국` | `true` | `car` | `true` |
| 벚꽃 명소 대중교통으로 가는 길 | `map_place` | `place_search` | `벚꽃` | `true` | `transit` | `true` |
| 장미 지도에서 보여줘 | `map_place` | `place_search` | `장미` | `false` | `none` | `true` |

## 6. Repair Prompt 명세

Repair Prompt는 Planner가 낸 JSON이 서버 계약을 어겼을 때 한 번만 호출된다. 목적은 사용자 문장을 새로 해석하는 것이 아니라, 같은 의도를 유지하면서 JSON 구조 오류를 고치는 것이다.

Repair 대상 예:

- JSON 형식이 깨진 경우
- 허용되지 않은 `domain/task`를 반환한 경우
- `domain=general`인데 `needs_screen=true`이거나 화면 action 성격의 task가 필요한 경우
- `community/open_composer` 요청인데 검색 task를 반환한 경우
- `unsupported` 요청인데 화면 이동이 필요한 것처럼 반환한 경우
- `app_navigation/open_map`인데 특정 꽃 keyword가 있고 장소 검색 의도가 명확한 경우
- 길찾기 요청인데 `route_request=false`인 경우
- 최신글/인기글 전체 조회인데 `keyword`에 “최신”, “이번 주”, “인기” 같은 요청 방식이 들어간 경우

Repair Prompt도 한국어 규칙을 중심으로 작성하되, 출력 JSON 필드명과 enum 값은 코드 계약과 동일하게 유지한다.

## 7. 서버 실행 매핑

Planner는 도구 이름과 action 이름을 직접 고르지 않는다. 서버가 `domain/task`를 보고 실행한다.

| domain/task | 실행되는 도구 또는 action |
|---|---|
| `flower_info/basic_info` | `flower.getBasicInfo` |
| `flower_info/meaning_bloom` | `flower.getMeaningAndBloom` |
| `flower_info/grow_guide` | `flower.getGrowGuide` |
| `flower_info/monthly_recommendation` | `flower.recommendByMonth` |
| `flower_info/candidate_inference` | `flower.inferCandidates` |
| `festival_info/*` | `festival.searchFlowerFestivals` |
| `community/search_posts` | `community.searchPosts` |
| `community/latest_posts` | `community.getLatestPosts` |
| `community/popular_posts` | `community.getPopularPosts` |
| `community/open_community` | `NAVIGATE COMMUNITY` |
| `community/open_composer` | `NAVIGATE COMMUNITY_COMPOSE` |
| `map_place/place_search` | `flower.searchFlowerSpots` |
| `app_navigation/open_map` | `NAVIGATE MAP` |
| `app_navigation/open_flower_book` | `NAVIGATE FLOWER_BOOK` |
| `app_navigation/open_walk` | `NAVIGATE WALK` |
| `app_navigation/open_saved` | `NAVIGATE SAVED` |
| `unsupported/*` | `app.unsupported`, action 없음 |

## 8. Answer Prompt 명세

### 8.1 역할

Answer Prompt는 Planner와 서버 도구 실행 이후에 호출된다. 입력으로는 사용자 메시지, 세션 히스토리, 도구 결과, 앱에서 실행할 action 요약이 들어간다.

Answer Prompt의 역할은 도구 결과를 사용자에게 자연스럽게 설명하는 것이다. 새 도구를 선택하거나 새로운 사실을 만들어내면 안 된다.

### 8.2 공통 답변 원칙

- 한국어 질문에는 한국어로 답한다.
- 내부 route, action, tool 이름을 사용자에게 직접 말하지 않는다.
- `Tool results`, `Agent`, `route`, `task`, `params` 같은 내부 실행 표현을 노출하지 않는다.
- 도구 결과에 없는 꽃말, 개화일, 장소, 축제, 게시글을 만들어내지 않는다.
- 데이터가 없으면 없다고 말한다.
- 화면 이동 action이 있어도 정보 답변을 먼저 하고, 화면 이동 안내는 뒤에 짧게 붙인다.
- 커뮤니티 작성, 저장, 좋아요, 댓글, 삭제, 구매, 예매, 퀘스트 인증은 직접 수행했다고 말하지 않는다.
- 이름 모르는 꽃 후보 추정은 확정 표현을 금지한다.

### 8.3 권장 답변 순서

1. 직접 답변
2. 핵심 정보 2~4개
3. 데이터 없음, 출처 없음, API 실패, 지원 범위 제한 안내
4. 필요한 경우에만 다음 행동 제안 또는 화면 이동 안내

## 9. Domain별 답변 스타일

### 9.1 꽃 정보

꽃 정보 답변은 DB 기반 정보를 먼저 제공한다.

- `basic_info`: 이름, 학명, 설명, 이미지/출처가 있으면 함께 안내한다.
- `meaning_bloom`: 꽃말과 개화 정보를 분리해 말한다. 비어 있으면 추측하지 않는다.
- `grow_guide`: DB의 재배 팁 안에서만 답한다. 일반 원예 상식으로 보강하지 않는다.
- `monthly_recommendation`: 3~5개만 추천하고 이유를 짧게 말한다.
- `candidate_inference`: “가능성 있는 후보”라고 말하고 확정하지 않는다.

### 9.2 커뮤니티

커뮤니티 답변은 게시글 조회 결과를 짧게 요약한다.

- `search_posts`: 검색 keyword와 일치하는 후기/게시글을 안내한다.
- `latest_posts`: 최신순으로 조회된 글을 소개한다. keyword가 비어 있으면 전체 최신글이다.
- `popular_posts`: 좋아요, 댓글, 최신순 기준 인기글을 안내한다. 조회수 기준이라고 말하지 않는다.
- `open_composer`: 작성 화면 이동만 안내한다. 글을 대신 작성하거나 저장했다고 말하지 않는다.
- 결과가 없으면 “현재 조회된 글이 없습니다”처럼 명확히 말한다.

### 9.3 축제

축제 답변은 Tour API 도구 결과만 근거로 한다.

- 이미 지난 축제는 추천하지 않는다.
- API 키 없음, API 실패, 결과 없음이면 축제명을 생성하지 않는다.
- 기간 조건이 있으면 “이번 주 기준”, “이번 달 기준”, “다가오는 축제 기준”처럼 조회 기준을 말한다.
- 지도 action이 있으면 축제 정보를 먼저 말하고, 마지막에 지도 화면 안내를 붙인다.

### 9.4 지도와 장소

지도/장소 답변은 장소 데이터와 화면 action을 분리해 말한다.

- 장소 결과가 있으면 대표 장소, 주소, 거리 정보가 있는 경우만 안내한다.
- 장소 결과가 없으면 등록된 장소가 없다고 말한다.
- 검색어 적용이나 지도 이동은 보조 안내로 짧게 말한다.
- 길찾기는 목적지와 이동수단이 준비된 경우에만 실행 안내를 한다.
- 현재 위치가 없거나 장소 결과가 없으면 길찾기를 실행했다고 말하지 않는다.

### 9.5 미지원 요청

미지원 요청은 짧고 명확하게 거절한다.

- 자동 게시, 댓글, 좋아요, 삭제, 구매, 예매, 퀘스트 인증, 관리자 기능은 실행하지 않는다.
- 사용자가 직접 해당 화면에서 해야 하는 작업이면 그렇게 안내한다.
- action은 만들지 않는 것이 원칙이다.

## 10. Fallback Reply 명세

Fallback Reply는 Spring AI 호출이 실패하거나 API 키가 없을 때 사용한다. 목표는 개발자 오류 메시지를 사용자에게 그대로 노출하지 않고, 이미 서버가 실행한 도구 결과와 action 요약을 기반으로 최소 응답을 제공하는 것이다.

Fallback 응답 원칙:

- OpenAI API 키, HTTP status, 예외 stack trace를 사용자에게 말하지 않는다.
- 도구 결과가 있으면 그 결과를 기반으로 안내한다.
- 도구 결과가 없으면 지원 범위 안에서 짧게 안내한다.
- 미지원 요청은 `app.unsupported` 결과를 우선한다.

## 11. 프롬프트 수정 절차

프롬프트를 수정할 때는 아래 순서로 진행한다.

1. 어떤 실패인지 구분한다.
   - Planner가 `domain/task`를 잘못 골랐는지
   - 서버 실행 매핑이 잘못됐는지
   - 도구 결과가 부족한지
   - Answer Prompt가 결과를 이상하게 표현했는지
2. Planner 문제면 예시와 필드 의미를 보강한다.
3. Answer 문제면 Base Prompt 또는 Domain Style Prompt를 보강한다.
4. 문장별 보정 목록을 늘리는 방식은 마지막 수단으로만 사용한다.
5. 평가 스크립트로 도구 선택과 action을 확인한다.
6. 실제 서버에서 답변 문장에 내부 실행 정보가 새지 않는지 확인한다.

## 12. 검증 기준

프롬프트 변경 후 최소 확인 대상:

```powershell
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set community-smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set festival-smoke -ShowDetails
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set map-smoke -ShowDetails
```

확인할 항목:

- Planner가 AIPlanner로 동작하는지
- 의도한 `domain/task`가 선택되는지
- 최신글/인기글 전체 조회에서 `keyword`가 빈 문자열인지
- 꽃 주제가 있는 최신글/인기글에서 `keyword`가 유지되는지
- 미지원 요청에서 action이 없는지
- 최종 답변에 내부 route/action/tool 이름이 노출되지 않는지
- 데이터가 없을 때 없는 정보를 만들어내지 않는지

## 13. 유지보수 원칙

- 새 도구를 추가하면 Planner domain/task, 서버 실행 매핑, Answer 스타일, 평가셋을 함께 갱신한다.
- 프롬프트 예시는 기능별 대표 사례만 둔다.
- “특정 문장을 보면 특정 결과” 방식으로 문장별 하드코딩하지 않는다.
- 프롬프트 원문과 문서가 달라지면 코드가 우선이며, 문서는 즉시 갱신한다.
- 비밀키, 서버 환경변수 값, 운영 데이터 원문은 문서에 넣지 않는다.
