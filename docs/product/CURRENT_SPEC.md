# HAR_FLOWER 현재 프로젝트 명세
<!-- 2026-05-14 daily-bug-scan: 챗봇 커뮤니티 작성 화면 이동 액션과 명세 반영 상태를 갱신함. -->

- 문서 버전: v1.1.0
- 최종 반영일: 2026-05-14

## 1. 프로젝트 개요

HAR_FLOWER는 Flutter 앱과 Spring Boot 백엔드로 구성된 꽃 위치/도감/커뮤니티/산책 앱이다. 현재 저장소는 다음 영역으로 나뉜다.

- `flower_app/`: Flutter 클라이언트
- `flower-backend/`: Spring Boot API 서버
- `docs/`: 제품/API 명세
- `scripts/`: 작업 보고서와 Git hook 스크립트
- `REPORT/`: 커밋 대상이 아닌 로컬 작업 보고서

AGENTS.md 기준 AI 작업 권한은 AI 챗봇 기능과 앱 제어 연결부에 있다. 지도, 커뮤니티, 도감, 산책/포인트 내부 구현은 명시 승인 없이 수정하지 않는다.

## 2. 개발 환경

- Backend: Spring Boot 3.4.1, Java 17, Spring AI 1.0.5
- Frontend: Flutter 최신 SDK 계열, Dart SDK `^3.11.5`
- Database: PostgreSQL/PostGIS 운영 전제, 로컬 기본 설정은 H2
- Cloud: Oracle Cloud Object Storage 사용 구조 포함
- Auth/Push: Kakao OAuth, JWT, Firebase Messaging
- 외부 데이터: 농사로 오늘의 꽃 API, Tour API, Kakao Map

운영 DB는 PostgreSQL을 전제로 하며, `docker-compose.yml`에는 `postgis/postgis:16-3.4` 컨테이너가 정의되어 있다. `application.yml`의 기본 datasource는 로컬 개발/테스트용 H2이다.

## 3. Flutter 앱 현황

주요 진입점은 `flower_app/lib/main.dart`이다. 앱 시작 시 `.env`, Firebase, SharedPreferences를 초기화하고 access token 존재 여부에 따라 로그인 화면 또는 메인 화면으로 진입한다.

주요 화면은 다음과 같다.

- `MainScreen`: 홈, 챗봇 입력, 커뮤니티 미리보기, 산책 요약, 주요 화면 바로가기
- `ChatScreen`/`ChatbotScreen`: 챗봇 전용 대화 화면
- `KakaoMapScreen`: 지도 화면, 챗봇 지도 액션을 `initialActions`로 수신
- `FlowerBookPage`/`FlowerBookScreen`: 꽃 도감 화면
- `CommunityFeedScreen`/`CommunityScreen`: 커뮤니티 피드
- `CreatePostScreen`: 커뮤니티 글 작성
- `PedometerScreen`/`WalkScreen`: 산책/만보기
- `SavedPage`/`SavedScreen`: 저장 항목
- `LoginScreen`, `ProfileSetupScreen`, `MyInfoScreen`: 인증/사용자 화면

앱의 백엔드 주소는 `ApiConfig.backendBaseUrl()`에서 결정된다. `.env`의 `BACKEND_URL`이 우선이며 Android 에뮬레이터에서는 기본값으로 `http://10.0.2.2:8080`을 사용한다.

## 4. Spring Boot 백엔드 현황

백엔드는 `com.flower.backend` 패키지 아래에 도메인별로 구성되어 있다.

- `auth`: Kakao OAuth, JWT, refresh, profile setup, FCM token, logout
- `flower`: 꽃 도감/꽃 위치 데이터, 카테고리, 월별 조회, 검색, 학명 매칭, 관리자 import
- `community`: 커뮤니티 피드, 글 작성, 좋아요, 저장
- `chatbot`: Spring AI 기반 챗봇, 라우팅, 도구 실행, 앱 액션 반환
- `storage`: 로컬 저장소와 Oracle Object Storage 추상화
- `common`: 공통 API 응답, 예외 처리

공통 응답 형식은 두 계열이 존재한다.

- `com.flower.backend.common.response.ApiResponse`: 일반 API에서 사용, `success`, `data`, `error` 포함
- `com.flower.backend.common.dto.ApiResponse`: 챗봇 API에서 사용, `success`, `data` 포함

## 5. 기능별 현재 범위

### AI 챗봇

챗봇은 사용자 메시지를 받아 라우팅 의도를 판단하고, 필요한 도구 결과와 Flutter 앱 제어 액션을 함께 반환한다. 상세 명세는 `docs/product/chatbot/CHATBOT_COMMON_SPEC.md`와 도구별 문서를 따른다.

### 지도

지도 화면은 Flutter의 `KakaoMapScreen`과 `assets/map/`의 WebView 기반 지도 자산으로 구성된다. 챗봇은 지도 내부 구현을 직접 수정하지 않고 `NAVIGATE MAP`, `MAP_SET_SEARCH_QUERY`, `MAP_SHOW_FLOWER`, `MAP_OPEN_FLOWER_PREVIEW` 같은 액션으로 지도 화면에 요청을 전달한다.

### 꽃 도감/꽃 데이터

백엔드 `FlowerController`는 카테고리, 월별 꽃, 상세, 검색, 학명 매칭 API를 제공한다. Flutter의 `FlowerBookApiService`가 도감 화면에서 이를 호출한다. 별도로 `FlowerApiService`는 농사로 오늘의 꽃 API를 직접 호출하는 클라이언트 서비스이다.

### 커뮤니티

백엔드 `CommunityController`는 피드 조회, 게시글 작성, 좋아요, 저장 토글을 제공한다. Flutter의 `CommunityApiService`가 access token을 포함해 호출한다. 챗봇 커뮤니티 도구는 커뮤니티 글 검색과 화면 이동/작성 화면 이동 액션만 담당하며 실제 글 저장이나 초안 생성은 수행하지 않는다. 현재 글 작성 요청은 `NAVIGATE COMMUNITY_COMPOSE`로 `CreatePostScreen` 연결까지만 수행한다.

# 커뮤니티 아이디어 - 하단 네비게이션으로 커뮤니티 들어오면 게시글 | 인기글 | 댓글 내역 으로 네비게이션 변경

### 산책/포인트

Flutter에는 산책/만보기 화면과 서비스가 존재한다. 현재 확인된 백엔드 컨트롤러 목록에는 산책/포인트 전용 컨트롤러가 없다. 챗봇은 `NAVIGATE WALK` 액션으로 산책 화면 이동만 계획할 수 있다.

### 저장 항목

Flutter의 `SavedApiService`는 `/api/v1/saved/posts`, `/api/v1/saved/spots` 계열 API를 호출하도록 작성되어 있다. 현재 확인된 백엔드 컨트롤러 목록에는 saved 전용 컨트롤러가 없다.

## 6. 문서 분리 기준

- 전체 현황: 이 문서
- 챗봇 공통 동작: `docs/product/chatbot/CHATBOT_COMMON_SPEC.md`
- 챗봇 지도 도구: `docs/product/chatbot/tools/MAP_AGENT_SPEC.md`
- 챗봇 꽃/도감 도구: `docs/product/chatbot/tools/FLOWER_AGENT_SPEC.md`
- 챗봇 커뮤니티 도구: `docs/product/chatbot/tools/COMMUNITY_AGENT_SPEC.md`
- 챗봇 API 계약: `docs/api/CHATBOT_API.md`
- 전체 API 색인: `docs/api/CURRENT_API_SUMMARY.md`
