# 🔐 AUTH 모듈 — 인증/회원 시스템

> **담당:** 백엔드 서버
> **기술 스택:** JWT (Access/Refresh Token) + OAuth 2.0 (Google, Naver)
> **역할:** 회원가입, 로그인, 소셜 로그인, 토큰 발급 및 검증

---

## 📂 패키지 구조

```
auth/
├── User.java                   # 회원 엔티티 (DB 테이블 매핑)
├── UserRepository.java         # DB 조회/저장 인터페이스
├── AuthDto.java                # 요청/응답 데이터 형식 모음
├── AuthException.java          # 인증 관련 커스텀 예외
├── AuthService.java            # 핵심 비즈니스 로직 (회원가입/로그인/소셜)
├── AuthController.java         # API 엔드포인트 (외부와 통신하는 창구)
├── JwtProvider.java            # JWT 토큰 발급 및 검증
├── JwtAuthenticationFilter.java# 모든 요청에서 JWT 자동 검사하는 필터
├── SecurityConfig.java         # Spring Security 보안 설정
├── OAuthProperties.java        # 소셜 API 키를 YAML에서 읽어오는 설정
└── OAuthService.java           # 구글/네이버 서버와 실제 HTTP 통신
```

---

## ⚠️ 깃에 올라가지 않는 파일 1개 (로컬에 직접 만들어야 함!)

> 이 파일은 **소셜 로그인 API 키**를 담고 있어서 `.gitignore`로 보호되어 있습니다.
> 클론 직후에는 **존재하지 않으므로** 아래 방법대로 직접 만들어야 서버가 정상 실행됩니다.

| 파일 경로 | 역할 |
|-----------|------|
| `src/main/resources/application-auth.yml` | 구글/네이버 OAuth 클라이언트 키 설정 |

---

## 🔑 소셜 로그인 API 키 발급받기

### Google OAuth 키 발급

1. [Google Cloud Console](https://console.cloud.google.com) 접속
2. 프로젝트 선택 (또는 신규 생성)
3. **API 및 서비스 > 사용자 인증 정보** 이동
4. **[+ 사용자 인증 정보 만들기] → OAuth 2.0 클라이언트 ID** 선택
5. 애플리케이션 유형: **Android** 또는 **iOS** 선택
6. 생성 완료 후 **클라이언트 ID**와 **클라이언트 보안 비밀(Secret)** 복사

### Naver OAuth 키 발급

1. [네이버 개발자 센터](https://developers.naver.com/apps) 접속
2. **Application 등록** 클릭
3. 사용 API: **네아로(네이버 아이디로 로그인)** 선택
4. 환경 추가: **Android** 또는 **iOS** 선택
5. 등록 완료 후 **Client ID**와 **Client Secret** 복사

---

## 📄 `application-auth.yml` 파일 생성

아래 경로에 파일을 새로 만듭니다:

```
flower-backend/
└── src/
    └── main/
        └── resources/
            └── application-auth.yml   ← 여기에 생성!
```

파일 내용 (발급받은 키 값을 직접 채워 넣으세요):

```yaml
spring:
  security:
    jwt:
      secret-key: "여기에_32자_이상의_랜덤_문자열_입력"  # JWT 서명에 사용하는 비밀키
      access-token-expiry: 3600        # Access Token 유효시간 (초) — 1시간
      refresh-token-expiry: 2592000    # Refresh Token 유효시간 (초) — 30일

oauth:
  google:
    client-id: "여기에-구글-클라이언트-ID"
    client-secret: "여기에-구글-시크릿"
  naver:
    client-id: "여기에-네이버-Client-ID"
    client-secret: "여기에-네이버-Client-Secret"
```

> **JWT Secret Key 생성 팁:** 아무 랜덤 문자열이나 32자 이상 입력하면 됩니다.
> 예: `flower-jwt-secret-key-2026-very-long-string-here`

---

## 🌐 API 엔드포인트 목록

Base URL: `http://localhost:8080/api/v1/auth`

| 메서드 | 경로 | 설명 | 인증 필요 |
|--------|------|------|-----------|
| `POST` | `/signup` | 이메일/비밀번호 회원가입 | ❌ |
| `POST` | `/login` | 이메일/비밀번호 로그인 | ❌ |
| `POST` | `/refresh` | Access Token 재발급 | ❌ |
| `POST` | `/logout` | 로그아웃 (FCM 토큰 초기화) | ✅ |
| `POST` | `/oauth/google` | 구글 소셜 로그인 | ❌ |
| `POST` | `/oauth/naver` | 네이버 소셜 로그인 | ❌ |
| `POST` | `/nickname` | 소셜 신규 회원 닉네임 설정 | ❌ (tempToken) |
| `POST` | `/fcm-token` | FCM 기기 토큰 등록/갱신 | ✅ |

---

## 🔄 소셜 로그인 흐름

```
1. 앱(Flutter) → 구글/네이버 SDK로 auth_code 발급받기
2. 앱 → 서버: auth_code + redirect_uri 전송
3. 서버 → 구글/네이버 서버: auth_code를 Access Token으로 교환
4. 서버 → 구글/네이버 서버: Access Token으로 유저 프로필(이메일, 닉네임) 조회
5. 서버: 기존 회원? → 바로 로그인 토큰 발급
          신규 회원? → tempToken + isNewUser:true 반환
6. 신규 회원인 경우 앱에서 닉네임 입력 화면 보여줄 것
7. 닉네임 확정 후 /nickname 엔드포인트 호출 → 최종 가입 및 토큰 발급
```

---

## 🔒 인증이 필요한 API 호출 방법

로그인 성공 후 받은 **Access Token**을 모든 요청 헤더에 포함합니다:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## ✅ 설정 확인

파일을 올바르게 만들었다면 서버 시작 시 별다른 오류 없이 구동됩니다.

Swagger 또는 Postman으로 아래 요청이 정상 응답하면 준비 완료:

```
POST http://localhost:8080/api/v1/auth/signup
Content-Type: application/json

{
  "email": "test@flower.com",
  "password": "Test1234!",
  "nickname": "꽃사랑"
}
```

---

## 🚫 자주 하는 실수

| 증상 | 원인 | 해결 |
|------|------|------|
| 서버 시작 시 `jwt.secret-key` 오류 | `application-auth.yml` 없음 | 위 파일 생성 안내 참고 |
| 소셜 로그인 시 `INVALID_OAUTH_CODE` | API 키가 잘못되었거나 비어있음 | yml 파일에 실제 키 값 입력 확인 |
| `401 Unauthorized` | Authorization 헤더 누락 또는 토큰 만료 | Access Token 재발급 후 재시도 |
| `EMAIL_ALREADY_EXISTS` | 같은 이메일로 다른 방식(일반/소셜) 중복 가입 시도 | 기존 로그인 방식 사용 |
