# PR6 액션 계약과 회귀 테스트 보강

## Summary
AI 기능의 실제 계약은 `NAVIGATE`뿐 아니라 지도 검색, 꽃 미리보기, 길찾기, 커뮤니티 작성 화면 이동까지 포함합니다. DTO 주석, 문서, 테스트가 이 범위를 충분히 따라오지 못하고 있어 마지막 PR에서 계약을 정리하고 회귀 테스트를 보강합니다.

## 문제
- 백엔드 `ChatAction` 주석은 현재 지원 액션 범위를 정확히 설명하지 않습니다.
- Flutter SSE 파싱, 플로팅 상태, 액션 실행 런타임 테스트가 부족합니다.
- 문서와 실제 처리 가능한 target/type 목록이 어긋날 수 있습니다.

## 변경 범위
- `flower-backend/src/main/java/com/flower/backend/chatbot/dto/ChatAction.java`
  - 현재 지원 액션 타입과 target 기준으로 주석 정리
- `docs/api/CHATBOT_API.md`
  - SSE 이벤트와 action/actions 계약 최신화
- `docs/product/CURRENT_SPEC.md`
  - AI 챗봇 앱 제어 범위 최신화
- `flower-backend/src/test/java/com/flower/backend/chatbot/service/ChatbotServiceTest.java`
  - 액션 계약 회귀 테스트 보강
- Flutter 테스트
  - SSE 파서 테스트
  - `AppActionRuntime` target/type 매핑 테스트
  - 가능하면 플로팅 챗봇 상태 전이 widget test 추가

## 제외
- 새 기능 추가
- UI 디자인 변경
- API 응답 필드 삭제 또는 breaking change

## 검증
- `flutter analyze --no-pub`
- `flutter test --no-pub`
- `.\gradlew.bat test`
- 문서의 action 목록과 `ChatActionValidator`, `AppActionRuntime`, `KakaoMapScreen` 처리 목록 대조

