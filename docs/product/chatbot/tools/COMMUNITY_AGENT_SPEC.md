# CommunityAgent 명세

## 1. 책임

CommunityAgent는 커뮤니티 게시글을 검색하고, 커뮤니티 화면 이동 또는 글 작성 초안 준비 액션을 만든다. 실제 게시글 생성/저장/수정은 커뮤니티 API와 화면의 책임이며 챗봇 도구는 수행하지 않는다.

구현 위치:

- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/CommunityAgent/CommunityTools.java`
- 데이터 저장소: `PostRepository`
- Flutter 실행: `AppActionRuntime`의 `COMMUNITY` 화면 이동

## 2. 제공 도구

CommunityAgent의 각 도구는 하나의 커뮤니티 관련 기능만 수행한다. 게시글 검색, 커뮤니티 화면 열기, 글 작성 초안 준비는 서로 합치지 않고 별도 도구와 별도 액션으로 유지한다.

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
        "nickname": "사용자",
        "content": "게시글 내용",
        "species": "벚꽃",
        "likes": 4
      }
    ]
  }
}
```

- 실패 ToolResult:

```json
{
  "tool": "community.searchPosts",
  "status": "ERROR",
  "summary": "Community post search failed.",
  "error": "게시글 검색 중 오류가 발생했습니다."
}
```

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

### `prepareDraft(topic)`

- 목적: 커뮤니티 글 작성 초안 준비
- AI 설명: `Prepare a draft-only community post action without saving a post.`
- 한국어 설명: 게시글을 저장하지 않고 커뮤니티 글 작성 초안 액션만 준비한다.
- 입력: 선택 topic, 최대 80자
- 입력 한국어 설명: 커뮤니티 글 초안에 사용할 선택 주제
- 출력 액션:

```json
{
  "type": "PREPARE_DRAFT",
  "target": "COMMUNITY",
  "params": {
    "mode": "DRAFT_ONLY",
    "topic": "벚꽃 산책 후기"
  }
}
```

## 3. 라우팅 규칙

- 커뮤니티, 게시글, 후기, 글 관련 요청은 `COMMUNITY` intent로 분류된다.
- 글 작성/초안 성격의 요청은 `PREPARE_DRAFT`를 사용한다.
- 검색/보기 성격의 요청은 `searchPosts`와 `NAVIGATE COMMUNITY`를 사용할 수 있다.
- Flutter 현재 실행부는 `PREPARE_DRAFT`를 화면 액션으로 인식하지만 target이 `COMMUNITY`이면 `CommunityFeedScreen`으로 이동한다. 초안 내용을 작성 화면에 자동 주입하는 연결은 현재 명세 범위 밖이다.

## 4. 응답 원칙

- 게시글 검색 결과는 nickname, content, species, likes만 챗봇 응답 근거로 사용한다.
- 글 작성 요청은 초안 준비만 수행한다.
- 실제 게시글 저장, 이미지 첨부, 위치 첨부는 커뮤니티 화면/API에서 사용자가 직접 수행해야 한다.

## 5. 실패 및 제한 사항

- 검색 실패 시 `status=ERROR` ToolResult를 반환하고 게시글 내용을 임의로 생성하지 않는다.
- access token이 필요한 실제 커뮤니티 API 호출과 달리 CommunityAgent 검색은 repository를 직접 사용한다.
- 커뮤니티 내부 화면/작성 기능 수정은 AGENTS.md 권한 밖이다.
