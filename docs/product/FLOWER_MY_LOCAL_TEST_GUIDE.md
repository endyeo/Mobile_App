# FLOWER_MY 로컬 테스트 폴더 운영 가이드

## 목적

`C:\FLOWER_MY`는 실제 개발 기준 폴더가 아니라 로컬 테스트를 빠르게 하기 위한 복제 폴더다.
기준 코드는 `C:\HAR_FLOWER`이며, 기능 개발과 커밋은 HAR_FLOWER에서 진행한다.

FLOWER_MY는 로컬 실행을 위해 일부 설정이 HAR_FLOWER와 다를 수 있다. 따라서 HAR_FLOWER의 파일을 통째로 덮어쓰면 로컬 연결 설정이 사라지거나 서버 배포용 설정으로 바뀔 수 있다.

## 절대 덮어쓰면 안 되는 로컬 설정

아래 항목은 FLOWER_MY 로컬 테스트를 위해 유지한다.

- `C:\FLOWER_MY\start-backend-local.ps1`
- `C:\FLOWER_MY\flower_app\.env`
- 로컬 백엔드 접속을 위한 `BACKEND_URL`
- Android Emulator 접속용 `http://10.0.2.2:8080`
- 로컬 실행만을 위해 조정한 포트, 프로필, 임시 실행 설정

특히 Flutter Debug Run에서는 Android Emulator가 PC의 `localhost`를 직접 볼 수 없으므로, 로컬 백엔드는 보통 아래 주소로 연결한다.

```text
BACKEND_URL=http://10.0.2.2:8080
```

반대로 실제 기준 코드인 HAR_FLOWER에는 이 로컬 전용 보정 로직을 강제로 넣지 않는다.

## HAR_FLOWER 변경을 FLOWER_MY에 반영하는 방법

FLOWER_MY에 반영할 때는 전체 폴더 복사가 아니라 기능 파일만 선별해서 반영한다.

예를 들어 챗봇 SSE 기능을 확인해야 한다면 아래 같은 파일만 비교해서 복사한다.

- `flower-backend/src/main/java/com/flower/backend/chatbot/controller/ChatbotController.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/dto/ChatMessageRequest.java`
- `flower-backend/src/main/java/com/flower/backend/chatbot/service/ChatbotService.java`
- `flower_app/lib/services/chatbot_service.dart`
- `flower_app/lib/widgets/chat_floating_button.dart`

복사 후에도 FLOWER_MY의 `.env`, 로컬 실행 스크립트, 로컬 연결 주소는 유지해야 한다.

## 로컬 테스트 실행 기준

백엔드는 FLOWER_MY 루트에서 실행한다.

```powershell
cd C:\FLOWER_MY
.\start-backend-local.ps1
```

Flutter는 `C:\FLOWER_MY\flower_app`을 기준으로 IDE의 Debug Run으로 실행한다.
이때 OpenAI API Key는 사용자가 직접 환경 변수로 설정해서 실행한다.

## 주의할 점

- FLOWER_MY에서 테스트가 성공해도 기준 코드는 HAR_FLOWER에 다시 반영해야 한다.
- FLOWER_MY에서 임시로 넣은 디버그 로그, `[SSE]` 표시, 로컬 URL 보정은 HAR_FLOWER에 그대로 가져오지 않는다.
- HAR_FLOWER의 배포/공용 설정을 FLOWER_MY에 통째로 복사하지 않는다.
- FLOWER_MY는 오래 두면 코드가 뒤처질 수 있으므로, 테스트할 기능 파일만 최신으로 맞춘다.
- 문제가 생기면 먼저 “서버가 8080으로 떠 있는지”, “Flutter `.env`가 `10.0.2.2:8080`을 보고 있는지”, “OPENAI_API_KEY가 백엔드 실행 터미널에 잡혀 있는지”를 확인한다.

## 현재 챗봇 SSE 기준 흐름

현재 기준 흐름은 아래와 같다.

```text
Flutter 질문 전송
-> POST /chatbot/message/stream SSE 연결
-> CONNECTED
-> STATUS
-> ACTION 수신 시 Flutter가 즉시 AppActionRuntime 실행
-> TOOL_RESULT
-> FINAL_ANSWER
-> DONE 수신 후 연결 정리
```

정지 버튼을 누르면 Flutter가 SSE 요청을 취소하고, 진행 중 말풍선을 제거하며, 최종 답변을 표시하지 않는다.
