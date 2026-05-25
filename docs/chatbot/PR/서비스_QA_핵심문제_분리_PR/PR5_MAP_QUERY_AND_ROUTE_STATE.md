# PR5 지도 검색어 반영과 길찾기 상태 분리

## 문제
`벚꽃 지도에서 보여줘`에서 지도는 열렸지만 검색창에 `벚꽃`이 들어가지 않았습니다. 데이터가 없을 때도 다시 지도 화면을 여는 행동이 반복됩니다. `장미 가는 길 알려줘`는 근처 꽃 명소 추천과 비슷한 답변이 나와 길찾기 의도가 충분히 분리되지 않았습니다.

## 목표
- 지도 action이 실제 검색창과 JS 지도 상태에 반영됩니다.
- 이미 지도 화면에 있을 때는 새 지도 화면을 또 push하지 않고 현재 지도 상태를 갱신합니다.
- 데이터가 없으면 지도 재오픈보다 "등록된 장소 없음"을 먼저 안내합니다.
- 길찾기 요청은 장소 검색과 답변 톤을 분리합니다.

## 변경 파일 후보
- `flower_app/lib/app_actions/app_action_runtime.dart`
- `flower_app/lib/screens/kakao_map_screen.dart`
- `flower_app/assets/map/app.js`
- `flower-backend/src/main/java/com/flower/backend/chatbot/service/ChatbotService.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/tool/FlowerAgent/FlowerToolService.java`

## 구현 계획
1. `AppActionRuntime`이 현재 화면이 `KakaoMapScreen`인지 알 수 있는 방법을 설계합니다.
2. 현재 지도가 열려 있으면 `Navigator.push` 대신 현재 지도 컨트롤러에 action을 전달합니다.
3. `MAP_SET_SEARCH_QUERY`는 `_searchController.text`와 `FlowerMap.setSearchQuery()`를 모두 갱신해야 합니다.
4. Web hash config와 native JS 실행 경로에서 동일하게 초기 검색어가 적용되는지 확인합니다.
5. 장소 결과 0건일 때는 `MAP_OPEN_FLOWER_PREVIEW`, `MAP_OPEN_ROUTE_CHOOSER`, `MAP_START_ROUTE`를 생성하지 않습니다.
6. `route_request=true`일 때 답변 템플릿을 별도로 둡니다.
   - 장소 있음: `장미 장소를 찾았어요. 이동수단을 선택하면 길찾기를 이어갈 수 있습니다.`
   - 장소 없음: `등록된 장미 장소가 없어 길찾기를 시작할 수 없습니다.`
7. `근처 꽃 명소 추천`과 `가는 길` 답변이 같은 문장으로 나오지 않게 합니다.

## 테스트
- 수동 질문:
  - `벚꽃 지도에서 보여줘`
  - `수국 위치 지도 열어줘`
  - `근처 꽃 명소 추천해줘`
  - `장미 가는 길 알려줘`
  - `장미까지 대중교통 길찾기`
- 기대:
  - 검색창에 꽃 이름 반영
  - 지도 상태 갱신 확인
  - 데이터 0건이면 미리보기/길찾기 action 없음
  - 길찾기 답변은 추천 답변과 다름

## 완료 기준
- 지도 검색어 누락이 재현되지 않습니다.
- 이미 지도에 있을 때 중복 화면 이동이 없습니다.
- 길찾기 의도와 장소 추천 의도가 답변/액션에서 분리됩니다.

