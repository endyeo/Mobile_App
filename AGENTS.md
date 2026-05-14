# AGENTS.md

## 작업 시작 규칙

- UI, 코드, 설정, 테스트를 변경하기 전에는 먼저 `scripts/start-work.ps1 -Name "<한국어 작업명>"`를 실행한다.
- `start-work.ps1`는 main/master에서는 작업 브랜치를 만들고, 활성 작업이 없으면 계획/기록을 생성한다.
- 활성 작업이 있으면 사소한 추가 변경도 같은 작업 기록에 append한다.
- 명세 문서의 오타, 표현, 구조 정리, 이미 구현된 사실 반영만 작업 브랜치와 REPORT를 생략할 수 있다.

## 현재 작업 권한

- 담당 범위는 AI 챗봇 기능이다.
- AI 챗봇이 앱을 제어하기 위해 필요한 라우팅, 액션 전달, 화면 이동 연결은 작업할 수 있다.
- 지도, 커뮤니티, 산책/포인트 등 다른 기능의 내부 구현은 명시 승인 없이 수정하지 않는다.
- 직접 추가한 기능은 수정할 수 있지만, API 계약, 백엔드, 다른 기능 화면 변경은 별도 승인 후 진행한다.

## 폴더 구성

```text
flower_app/       Flutter 앱
flower-backend/   Spring Boot 백엔드
docs/             제품 명세와 API 문서
docs/product/     제품 기능 명세, 챗봇 명세, 작업 보고서
docs/api/         API 문서
REPORT/           사용자 확인용 로컬 작업 보고서
```

## 작업 보고서

```text
REPORT/active/             커밋 전 활성 계획/기록
REPORT/plans/yyyyMMdd/     커밋 완료 후 작업 계획 보고서
REPORT/records/yyyyMMdd/   커밋 완료 후 작업 기록 보고서
```

- 커밋 전 계획의 체크박스가 모두 완료되어 있어야 한다.
- 커밋이 완료되면 활성 계획은 `REPORT/plans/yyyyMMdd/`, 기록은 `REPORT/records/yyyyMMdd/`로 이동된다.
- 보고 훅 설치는 `scripts/install-hooks.ps1`로 한다.
- AI는 사용자가 이전 작업 맥락 확인을 요청하거나, 이어서 작업해야 하는 경우에만 `REPORT/`를 읽는다.

## 금지 사항

- 비밀키 커밋 금지
- 커밋 메시지는 한국어로 작성
- 요청 없는 대규모 리팩터링 금지
- 팀원이 만든 기능 임의 삭제 금지
- 빌드 산출물 커밋 금지

## 테스트 명령

- Flutter 명령이 출력 없이 멈추면 sandbox/cache 권한 문제로 보고 권한을 올려 다시 실행한다.
- Flutter 앱: `cd flower_app`
- Flutter analyze: `flutter analyze --no-pub`
- Flutter test: `flutter test --no-pub`
- Spring Boot 백엔드: `cd flower-backend`
- Spring Boot test: `.\gradlew.bat test`
