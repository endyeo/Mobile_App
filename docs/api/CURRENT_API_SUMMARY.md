# HAR_FLOWER API 현황 요약

## 1. 기준

이 문서는 현재 백엔드 컨트롤러와 Flutter 서비스 호출 기준 API 색인이다. 챗봇 API 상세 계약은 `CHATBOT_API.md`를 따른다. 챗봇 외 API는 상세 스키마가 아니라 현재 노출된 엔드포인트 요약만 기록한다.

## 2. Auth API

Base path: `/api/v1/auth`

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| `POST` | `/refresh` | refresh token으로 token 재발급 | 없음 |
| `POST` | `/profile-setup` | 신규 사용자 프로필 설정 | temp token |
| `POST` | `/oauth/kakao` | Kakao auth code 처리 | 없음 |
| `POST` | `/fcm-token` | FCM token 저장 | Bearer |
| `POST` | `/logout` | 로그아웃 | Bearer |

추가 callback:

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/oauth/callback` | OAuth callback 처리 |

## 3. Flower API

Base path: `/api/v1/flowers`

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/categories` | 꽃 카테고리 목록 |
| `GET` | `/categories/{categoryId}` | 카테고리별 꽃 목록 |
| `GET` | `/monthly/{month}` | 월별 꽃 목록 |
| `GET` | `/{id}` | 꽃 상세 |
| `GET` | `/search?keyword={keyword}` | 꽃 검색 |
| `GET` | `/match?scientificName={name}&confidence={confidence}` | 학명 기반 매칭 |

관리자성 import API:

Base path: `/api/v1/admin/flowers`

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/import` | 꽃 데이터 import |
| `POST` | `/fetch-images` | 이미지 수집 |
| `POST` | `/compress-images` | 이미지 압축 |

## 4. Community API

Base path: `/api/v1/community`

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| `GET` | `/posts?cursor={cursor}&limit={limit}` | 커뮤니티 피드 조회 | Bearer |
| `POST` | `/posts` | multipart 게시글 작성 | Bearer |
| `POST` | `/posts/{postId}/like` | 좋아요 토글 | Bearer |
| `POST` | `/posts/{postId}/save` | 저장 토글 | Bearer |

게시글 작성 multipart 필드:

- `content`: 필수
- `flowerSpecies`: 선택
- `image`: 선택
- `latitude`: 선택
- `longitude`: 선택
- `address`: 선택

## 5. Chatbot API

Base path: `/chatbot`

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/message` | 챗봇 메시지 처리 |
| `DELETE` | `/session/{sessionId}` | 챗봇 세션 삭제 |

상세 계약은 `CHATBOT_API.md`를 따른다.

## 6. Flutter 서비스에는 있으나 현재 컨트롤러 색인에서 확인되지 않은 API

다음 경로는 Flutter 서비스에서 호출하지만 현재 확인된 백엔드 컨트롤러 목록에는 전용 컨트롤러가 없다.

| Flutter service | Method/Path | 용도 |
| --- | --- | --- |
| `SavedApiService` | `GET /api/v1/saved/posts` | 저장한 게시글 목록 |
| `SavedApiService` | `GET /api/v1/saved/spots` | 저장한 장소 목록 |
| `SavedApiService` | `DELETE /api/v1/saved/posts/{postId}` | 게시글 저장 해제 |
| `SavedApiService` | `DELETE /api/v1/saved/spots/{spotId}` | 장소 저장 해제 |

## 7. 외부 API/클라이언트 직접 호출

Flutter에서 직접 호출하는 외부 API:

- 농사로 오늘의 꽃 API: `http://apis.data.go.kr/1390804/NihhsTodayFlowerInfo01`
- Kakao OAuth authorize URL: `https://kauth.kakao.com/oauth/authorize`

앱 환경값:

- `BACKEND_URL`
- `KAKAO_MAP_KEY`
- `NONGSARO_API_KEY`
- `TOUR_API_KEY`

비밀키 값은 문서에 기록하지 않는다.
