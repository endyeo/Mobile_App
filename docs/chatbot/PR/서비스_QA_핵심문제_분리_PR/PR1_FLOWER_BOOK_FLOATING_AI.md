# PR1 꽃 도감 AI 플로팅 버튼 노출

## 문제
꽃 도감 화면에서 AI 플로팅 버튼이 보이지 않습니다. 사용자는 도감에서 꽃을 보다가 바로 질문하거나 지도/커뮤니티로 이어가야 하는데, 현재 도감 화면은 챗봇 접근성이 끊깁니다.

## 목표
- 꽃 도감 화면에서도 `ChatFloatingButton`을 사용할 수 있게 합니다.
- 기존 도감 검색/상세 UI를 깨지 않습니다.
- 플로팅 버튼이 도감 리스트, 상세, 검색창을 가리지 않도록 위치와 여백을 확인합니다.

## 변경 파일 후보
- `flower_app/lib/screens/flower_book_page.dart`
- `flower_app/lib/widgets/chat_floating_button.dart`

## 구현 계획
1. `FlowerBookPage`의 `Scaffold`에 `floatingActionButton: const ChatFloatingButton()`을 추가합니다.
2. `floatingActionButtonLocation: FloatingActionButtonLocation.endFloat`를 기존 챗봇 노출 화면과 동일하게 적용합니다.
3. 하단 버튼/검색 UI가 있다면 `SafeArea`와 bottom padding을 확인합니다.
4. 도감 화면에서 챗봇으로 `장미 키우는 법 알려줘`, `장미 지도에서 보여줘`를 실행해 overlay가 정상 유지되는지 확인합니다.

## 테스트
- Flutter 수동:
  - 도감 화면 진입
  - AI 플로팅 버튼 표시 확인
  - 챗봇 열기/닫기
  - 도감 검색창과 버튼 겹침 확인
- 명령:
  - `cd C:\HAR_FLOWER\flower_app`
  - `flutter analyze --no-pub`

## 완료 기준
- 꽃 도감 화면에 AI 플로팅 버튼이 보입니다.
- 버튼이 도감 검색/리스트/상세 사용을 방해하지 않습니다.
- 다른 화면의 플로팅 버튼 동작은 변하지 않습니다.

