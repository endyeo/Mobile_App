# PR 1 커뮤니티 최신글/인기글 500 해결 계획

- 작성일: 2026-05-22
- PR 목적: 커뮤니티 최신글/인기글 챗봇 요청의 서버 500 제거
- 기준 실패: 배포 서버 `community-smoke` C013~C019 HTTP 500

## Summary

이 PR은 커뮤니티 최신글/인기글 조회만 다룬다. 축제 timeout, Route AI 구조 변경, 꽃 개화 라우팅 개선은 포함하지 않는다.

배포 서버에서 `community.getLatestPosts`, `community.getPopularPosts` 경로가 `/chatbot/message` 전체 HTTP 500으로 이어지고 있다. 목표는 최신/인기글 요청이 항상 200 응답을 반환하고, 조회 실패 시에도 `ToolResult ERROR`와 사용자용 안내 답변으로 끝나게 만드는 것이다.

## Key Changes

- `community.getLatestPosts`는 우선 기존 안정 경로인 `findFeed(PageRequest.of(0, 5))` 기반으로 전체 최신글을 조회한다.
- `community.getPopularPosts`는 좋아요, 댓글, 최신순 정렬만 담당한다.
- 기간 필터는 유지하되, 운영 DB에서 기간 쿼리가 실패하면 전체 기간 조회로 fallback한다.
- 최신/인기 도구 내부 예외는 모두 잡아서 `ToolResult ERROR`로 반환한다.
- `/chatbot/message`는 커뮤니티 도구 실패 때문에 HTTP 500을 반환하지 않는다.
- `ToolResult.data`에 진단값을 넣는다.
  - `keyword`
  - `dateFilter`
  - `rangeStart`
  - `rangeEnd`
  - `periodFallbackUsed`
  - `queryFailed`

## Implementation Notes

- `CommunityTools.readPosts(...)`에서 repository 호출 실패를 최종 방어한다.
- `CommunityPostRepository`의 최신/인기 전용 JPQL이 운영 DB에서 문제를 내면, 더 단순한 repository 메서드로 분리한다.
- 기간 필터와 keyword 필터가 동시에 들어가는 복잡한 JPQL은 P1 이후 정교화 대상으로 둔다.
- 결과가 없으면 정상 빈 결과로 처리한다. 빈 결과는 실패가 아니다.

## Test Plan

로컬:

```powershell
cd C:\HAR_FLOWER
.\scripts\chatbot-evaluate.ps1 -BaseUrl http://localhost:8080 -Set community-smoke -ShowDetails
```

배포 서버:

```powershell
cd C:\HAR_FLOWER
.\scripts\chatbot-evaluate.ps1 -BaseUrl https://ourt.kro.kr -Set community-smoke -ShowDetails
```

필수 통과 케이스:

- `C013 최신 글들은 어떤 걸 소개 해?`
- `C014 최근 커뮤니티 글 보여줘`
- `C015 오늘 올라온 글 있어?`
- `C016 인기글 알려줘`
- `C017 이번 주 인기글 보여줘`
- `C018 3월 인기글 보여줘`
- `C019 장미 인기글 있어?`

통과 기준:

- `community-smoke`: 19/19 PASS
- HTTP 500 0건
- 최신/인기글 도구가 `community.searchPosts`로 회귀하지 않음
- 결과 없음은 정상 답변으로 처리

## Assumptions

- 이번 PR의 핵심은 “정확한 기간 필터 완성”이 아니라 “서비스 500 제거”다.
- 운영 DB 스키마 차이가 있으면 로그를 보고 repository 쿼리를 더 단순하게 조정한다.
- 커뮤니티 UI 변경은 포함하지 않는다.
