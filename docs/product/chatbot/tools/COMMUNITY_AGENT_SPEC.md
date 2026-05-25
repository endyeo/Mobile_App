# CommunityAgent 명세
<!-- 2026-05-14 daily-bug-scan: 커뮤니티 글 작성 액션을 COMMUNITY_COMPOSE 기반으로 반영함. -->
<!-- 반영: 2026-05-21 13:24 - CommunityPostRepository 기반으로 변경, searchByKeyword 쿼리 추가 반영 -->
<!-- 반영: 2026-05-25 sync - getLatestPosts/getPopularPosts 도구, address 검색 기준, 0건 SUCCESS, 제목 미생성 규칙, failureReason 정리, COMMUNITY_COMPOSE→CreateFlowerSpotScreen, 기간 필터 반영 -->

- 문서 버전: v1.4.0
- 최종 반영일: 2026-05-25

## 1. 책임

CommunityAgent는 커뮤니티 게시글을 검색하고, 커뮤니티 화면 이동 또는 글 작성 화면 이동 액션을 만든다. 실제 게시글 생성/저장/수정은 커뮤니티 API와 화면의 책임이며 챗봇 도구는 수행하지 않는다.

구현 위치:

- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/CommunityAgent/CommunityTools.java`
- 데이터 저장소: `CommunityPostRepository` (`searchByKeyword` 쿼리 포함) <!-- 반영: 2026-05-21 13:24 -->
- Flutter 실행: `AppActionRuntime`의 `COMMUNITY` 화면 이동

## 2. 제공 도구

CommunityAgent의 각 도구는 하나의 커뮤니티 관련 기능만 수행한다. 게시글 검색, 커뮤니티 화면 열기, 글 작성 화면 열기는 서로 합치지 않고 별도 도구와 별도 액션으로 유지한다.

도구 식별자와 `@Tool(description)`은 AI가 직접 읽는 값이므로 영어만 사용한다. 개발자가 읽는 한국어 설명은 코드 주석의 `KO:` 라인과 이 명세 문서에 남긴다.

### `searchPosts(query)`

- 목적: 커뮤니티 게시글 keyword 검색
- AI 설명: `Search FLOWER community posts by keyword.`
- 한국어 설명: 커뮤니티 게시글을 검색어 기준으로 조회한다.
- 입력: `query`
- 입력 한국어 설명: 커뮤니티 게시글 검색어
- 정규화: trim, 최대 100자
- 결과 제한: 최대 5개
- blank query: 전체 게시글 중 5개 조회
- 성공 ToolResult:

```json
{
  "tool": "community.searchPosts",
  "status": "SUCCESS",
  "summary": "'벚꽃' community search returned 3 result(s).",
  "data": {
    "items": [
      {
        "postId": 42,
        "nickname": "사용자",
        "content": "게시글 내용",
        "species": "벚꽃",
        "plantName": "왕벚나무",
        "address": "서울시 영등포구",
        "likes": 4,
        "commentCount": 2,
        "createdAt": "2026-05-20"
      }
    ]
  }
}
```

게시글에는 `title` 필드가 없다. 답변에서 게시글 제목을 지어내지 않는다. <!-- 반영: 2026-05-25 sync -->

검색 결과가 0건이면 `status=SUCCESS`, `items=[]`로 반환하고 오류로 처리하지 않는다. guarded answer에서 관련 글 없음으로 응답한다. <!-- 반영: 2026-05-25 sync -->

- 실패 ToolResult:

```json
{
  "tool": "community.searchPosts",
  "status": "ERROR",
  "summary": "Community post search failed.",
  "error": "게시글 검색 중 오류가 발생했습니다."
}
```

실패 시 `failureReason`에는 예외 클래스 이름만 포함하고, SQL/DB 예외 메시지는 응답에 노출하지 않는다. <!-- 반영: 2026-05-25 sync -->

### `getLatestPosts(dateFilter)` <!-- 반영: 2026-05-25 sync -->

- 목적: 커뮤니티 최신 게시글 조회
- AI 설명: `Get latest FLOWER community posts.`
- 한국어 설명: 커뮤니티 최신 게시글을 작성일 내림차순으로 조회한다.
- 입력: `dateFilter` (선택, `today`/`this_week`/`this_month`/`none`)
- 정렬: `createdAt DESC`
- 결과 제한: 최대 5개
- 성공/실패 ToolResult: `searchPosts`와 동일한 구조

### `getPopularPosts(dateFilter)` <!-- 반영: 2026-05-25 sync -->

- 목적: 커뮤니티 인기 게시글 조회
- AI 설명: `Get popular FLOWER community posts by likes and comments.`
- 한국어 설명: 커뮤니티 인기 게시글을 좋아요, 댓글, 작성일 순으로 조회한다.
- 입력: `dateFilter` (선택, `today`/`this_week`/`this_month`/`none`)
- 정렬: `likeCount DESC, commentCount DESC, createdAt DESC`
- 결과 제한: 최대 5개
- 성공/실패 ToolResult: `searchPosts`와 동일한 구조

### `openCommunity(keyword)`

- 목적: 커뮤니티 화면 열기
- AI 설명: `Prepare an internal client follow-up that opens the community screen.`
- 한국어 설명: 커뮤니티 화면을 여는 앱 내부 액션을 준비한다.
- 입력: 선택 keyword, 최대 80자
- 입력 한국어 설명: 커뮤니티 화면에 전달할 선택 검색어
- 출력 액션:

```json
{
  "type": "NAVIGATE",
  "target": "COMMUNITY",
  "params": {
    "query": "벚꽃"
  }
}
```

keyword가 비어 있으면 `params`는 null일 수 있다.

### `openPostComposer(topic)`

- 목적: 커뮤니티 글 작성 화면 열기
- AI 설명: `Prepare an internal client follow-up that opens the community post composer.`
- 한국어 설명: 게시글을 저장하지 않고 커뮤니티 글 작성 화면 이동 액션만 준비한다.
- 입력: 선택 topic, 최대 80자
- 입력 한국어 설명: 현재 v1에서는 작성 화면에 자동 입력하지 않는 선택 주제
- 출력 액션:

```json
{
  "type": "NAVIGATE",
  "target": "COMMUNITY_COMPOSE",
  "params": null
}
```

## 3. 라우팅 규칙

- 커뮤니티, 게시글, 후기, 글 관련 요청은 `COMMUNITY` intent로 분류된다.
- 글 작성 요청은 `openPostComposer`를 통해 `NAVIGATE COMMUNITY_COMPOSE`를 사용한다.
- 검색/보기 성격의 요청은 `searchPosts`와 `NAVIGATE COMMUNITY`를 사용할 수 있다.
- Flutter 실행부는 `COMMUNITY_COMPOSE`를 `CreateFlowerSpotScreen`으로 연결한다. <!-- 반영: 2026-05-25 sync - 기존 CreatePostScreen에서 변경 -->

## 4. 응답 원칙

- 게시글 검색 결과는 postId, nickname, content, species, plantName, address, likes, commentCount, createdAt을 챗봇 응답 근거로 사용한다. <!-- 반영: 2026-05-25 sync - 필드 확장 -->
- 게시글에는 제목(title) 필드가 없다. 답변에서 게시글 제목을 지어내지 않는다. <!-- 반영: 2026-05-25 sync -->
- 글 작성 요청은 작성 화면 이동만 수행하며 초안 생성은 하지 않는다.
- 실제 게시글 저장, 이미지 첨부, 위치 첨부는 커뮤니티 화면/API에서 사용자가 직접 수행해야 한다.

## 5. 실패 및 제한 사항

- 검색 실패 시 `status=ERROR` ToolResult를 반환하고 게시글 내용을 임의로 생성하지 않는다. `failureReason`에는 예외 클래스 이름만 포함하고, SQL/DB 메시지가 섞일 수 있는 exception message는 응답에서 제외한다. <!-- 반영: 2026-05-25 sync -->
- 검색 결과 0건은 오류가 아니라 `SUCCESS` + 빈 `items`로 반환한다. <!-- 반영: 2026-05-25 sync -->
- access token이 필요한 실제 커뮤니티 API 호출과 달리 CommunityAgent 검색은 repository를 직접 사용한다.
- `topic` 입력은 현재 v1에서 작성 화면에 자동 주입되지 않는다.
- 커뮤니티 내부 화면/작성 기능 수정은 AGENTS.md 권한 밖이다.
