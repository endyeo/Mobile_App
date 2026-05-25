# FLOWER App UI Specification
<!-- 반영: 2026-05-25 sync - FAB 아이콘, 네비게이션 무전환, 정적 상태 보존, 메인 화면 레이아웃 변경, COMMUNITY_COMPOSE 대상 변경 반영 -->

- 문서 버전: v1.1.0
- 최종 반영일: 2026-05-25

이 문서는 `flower_app` (Flutter)의 현재 UI 설계 및 아키텍처에 대한 명세입니다.

## 1. 글로벌 테마 및 색상 시스템

앱은 계절(봄, 여름, 가을, 겨울)에 따라 메인 테마 색상이 변경되는 동적 테마 시스템을 사용합니다.
색상 정의는 `lib/theme/app_colors.dart`에 위치하며, 디자인 담당자는 이 파일만 수정하여 앱 전체의 분위기를 변경할 수 있습니다.

### 계절별 색상 (Season Colors)

| 계절 | Primary | Secondary | Background (Bg) | Accent |
| :--- | :--- | :--- | :--- | :--- |
| **봄** | 벚꽃 핑크 (`#FFFF8FAB`) | 연두 (`#FF98D9A4`) | 연한 핑크 화이트 (`#FFFFF0F5`) | 연한 핑크 (`#FFFFB7C5`) |
| **여름** | 바다 파랑 (`#FF2E86AB`) | 초록 (`#FF4CAF50`) | 연한 하늘색 (`#FFF0F9FF`) | 밝은 파랑 (`#FF81D4FA`) |
| **가을** | 단풍 주황 (`#FFD4622A`) | 갈색 (`#FFB5835A`) | 따뜻한 크림 (`#FFFFF8F0`) | 노란 주황 (`#FFFFCC80`) |
| **겨울** | 차분한 파랑 (`#FF5C7FA3`) | 회청색 (`#FF90A4AE`) | 눈처럼 밝은 흰색 (`#FFF5F8FC`) | 연한 회색 (`#FFB0BEC5`) |

### 공통 색상
- **카카오 버튼**: 노란색 (`#FFFEE500`), 텍스트 (`#FF3C1E1E`)
- **지도 배경**: `#FFE8F4E8`
- **기본 무채색**: White, Black, Grey100 ~ Grey700

## 2. 타이포그래피 (텍스트 스타일)

`lib/theme/app_text_styles.dart`에서 전체 텍스트 스타일을 중앙 관리합니다.

- **앱 타이틀**: 36pt, Bold, Letter spacing 2
- **서브 타이틀**: 14pt, Normal
- **화면 제목 (AppBar)**: 18pt, Bold
- **버튼**: Large (16pt Bold), Medium (15pt Semi-Bold)
- **메뉴 라벨 (바텀 네비게이션 등)**: 10pt, Semi-Bold

## 3. 주요 UI 컴포넌트 및 레이아웃

### 3.1 하단 내비게이션 바 (App Bottom Navigation)
앱의 주요 4가지 화면을 전환하는 글로벌 하단 네비게이션입니다.
- **높이**: 65px
- **탭 구성**:
  1. **홈 (Home)**: `MainScreen()` - 메인 화면 (날씨, 추천 꽃, 걷기 등)
  2. **지도 (Map)**: `KakaoMapScreen()` - 꽃 명소 및 길찾기 지도
  3. **커뮤니티 (Community)**: `CommunityFeedScreen()` - 유저 간 소통 피드
  4. **내 정보 (MyInfo)**: `MyInfoScreen()` - 사용자 프로필 및 설정
- **선택 효과**: 선택된 탭은 현재 계절의 `Primary` 색상과 `FontWeight.w800`으로 강조됩니다.
- **화면 전환**: `PageRouteBuilder`를 사용한 무전환(애니메이션 없음) 라우트로 동작합니다. 탭 이동 시 라우트 스택을 새 화면 하나로 정리합니다. <!-- 반영: 2026-05-25 sync -->

### 3.2 글로벌 챗봇 플로팅 버튼 (Chat Floating Button)
- 대부분의 화면에서 우측 하단에 떠 있는 플로팅 버튼(FAB)입니다.
- **아이콘**: `Icons.smart_toy_outlined` (챗봇 모양) <!-- 반영: 2026-05-25 sync -->
- 클릭 시 플로팅 입력창이 열리고, AI 도우미와 대화할 수 있습니다.
- 입력창 열림, 대화 내역 열림, 입력 중 텍스트 상태를 **정적(static) 변수**로 보존해 화면 전환 후에도 초기화되지 않습니다. <!-- 반영: 2026-05-25 sync -->
- 요청별 `requestId`로 이전 SSE 이벤트가 현재 답변을 덮지 않도록 필터링합니다. <!-- 반영: 2026-05-25 sync -->

## 4. 주요 화면 (Screens) 설계

| 화면 이름 | 컴포넌트 | 설명 |
| :--- | :--- | :--- |
| **로그인 / 프로필** | `login_screen.dart`<br>`profile_setup_screen.dart` | 카카오 소셜 로그인 버튼 및 초기 닉네임 설정 화면 |
| **메인 홈** | `main_screen.dart` | 날씨 기반 꽃 추천, 만보기 위젯, 바로가기 카드(꽃 도감/만보기/저장 통합), 축제 소식, 꽃 게시글 섹션. 챗봇 전용 입력창은 제거되었으며 FAB만 유지. ListView 좌우 패딩 제거되고 개별 섹션이 `_pagePadding`으로 여백 관리 <!-- 반영: 2026-05-25 sync --> |
| **지도** | `kakao_map_screen.dart` | 카카오맵 웹뷰 연동, 내 주변 꽃 명소 핀, 길찾기 모드 UI 제공 |
| **커뮤니티** | `community_feed_screen.dart`<br>`create_post_screen.dart`<br>`create_flower_spot_screen.dart`<br>`comment_bottom_sheet.dart` | 사용자 게시글 피드, 글 작성 화면, 댓글 바텀 시트 연동. 챗봇 `COMMUNITY_COMPOSE`는 `CreateFlowerSpotScreen` 연결 <!-- 반영: 2026-05-25 sync --> |
| **도감 및 보관함** | `flower_book_page.dart`<br>`saved_page.dart` | 사용자가 찾은 꽃 종류 리스트(도감), 저장한 장소나 게시글 보관함 |
| **만보기** | `pedometer_screen.dart` | 일일 걸음 수 게이지, 목표 달성 현황 등 시각적 피드백 제공 |
| **AI 챗봇** | `chat_screen.dart`<br>`message_bubble.dart` | AI와 대화하는 채팅창, 유저 메시지와 AI 메시지(버블) 레이아웃 구성 |

## 5. UI 개발 원칙
1. **중앙화된 테마 사용**: 하드코딩된 색상 및 폰트 사이즈 지양. 반드시 `SeasonTheme.getColors()`, `AppColors`, `AppTextStyles`를 사용합니다.
2. **반응형 패딩/마진**: `SafeArea`를 사용하여 노치와 시스템 바에 UI가 가려지지 않도록 합니다.
3. **일관된 챗봇 접근성**: 새로운 메인 컨텐츠 화면을 추가할 때 가급적 `ChatFloatingButton`을 포함해 AI 챗봇 접근성을 유지합니다.
