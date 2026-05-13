# Task Card: AUTH — 인증 (회원가입 / 로그인)

> **연관 PRD:** §4.0 AUTH-01 ~ AUTH-06
> **우선순위:** P0 (MVP 필수)
> **함께 읽기:** `FLOWER_CONTEXT.md` (항상 포함)

---

## 기능 요약

| ID | 요구사항 |
|----|----------|
| AUTH-01 | 이메일 + 비밀번호 회원가입/로그인 |
| AUTH-02 | 구글 소셜 로그인 |
| AUTH-03 | 카카오 소셜 로그인 |
| AUTH-04 | 네이버 소셜 로그인 |
| AUTH-05 | 소셜 최초 가입 시 닉네임 입력 |
| AUTH-06 | JWT Access + Refresh Token 인증 유지 |

---

## 외부 연동 API

| 서비스 | API 이름 | 용도 | 비용 |
| :--- | :--- | :--- | :--- |
| **구글** | Google OAuth 2.0 | 구글 계정 간편 로그인 | 무료 |
| **카카오** | Kakao Login API | 카카오 계정 간편 로그인 | 무료 |
| **네이버** | Naver Login API | 네이버 계정 간편 로그인 | 무료 |

---

## API 엔드포인트

### 2.1 회원가입
```
POST /auth/signup  (인증 불필요)
Body: { email, password (8자+, 영문+숫자), nickname (2~10자) }
→ 201: { user_id, email, nickname, created_at }
에러: EMAIL_ALREADY_EXISTS(409), INVALID_EMAIL_FORMAT(400), INVALID_PASSWORD_FORMAT(400)
```

### 2.2 로그인
```
POST /auth/login  (인증 불필요)
Body: { email, password }
→ 200: { access_token, refresh_token, expires_in, user: { user_id, nickname } }
에러: INVALID_CREDENTIALS(401)
```

### 2.3 토큰 갱신
```
POST /auth/refresh  (인증 불필요)
Body: { refresh_token }
→ 200: { access_token, expires_in }
에러: INVALID_REFRESH_TOKEN(401)
```

### 2.4 로그아웃
```
POST /auth/logout  (🔒 인증 필요)
Body: { refresh_token }
→ 200: { data: null }
```

### 2.5 소셜 로그인 (OAuth)
```
POST /auth/oauth/{provider}  (인증 불필요)
Path: provider = google | kakao | naver
Body: { auth_code, redirect_uri }

→ 200 (기존 회원): { is_new_user: false, access_token, refresh_token, expires_in, user }
→ 200 (신규 회원): { is_new_user: true, temp_token, provider, provider_email }

에러: INVALID_OAUTH_CODE(401), UNSUPPORTED_OAUTH_PROVIDER(400), OAUTH_UPSTREAM_ERROR(500)
```

### 2.6 닉네임 설정 (신규 소셜 회원)
```
POST /auth/nickname  (temp_token으로 인증)
Body: { temp_token, nickname (2~10자) }
→ 201: { access_token, refresh_token, expires_in, user }
에러: TEMP_TOKEN_EXPIRED(401), NICKNAME_ALREADY_EXISTS(409), INVALID_NICKNAME_LENGTH(400)
```

---

## 소셜 로그인 흐름

```
[앱] SDK 호출 → [소셜 제공자] auth_code 반환
[앱 → 서버] auth_code 전달
[서버] 소셜 API로 사용자 정보 조회
  → 신규: 계정 생성 + temp_token 발급 → 닉네임 설정 → JWT 발급
  → 기존: JWT 바로 발급
```

---

## 작업 체크리스트

- [ ] Flutter: 로그인/회원가입 화면 UI
- [ ] Flutter: Google/Kakao/Naver SDK 연동
- [ ] Flutter: 토큰 저장 (secure storage)
- [ ] Flutter: 닉네임 설정 화면 (소셜 신규 회원용)
- [ ] 서버: /auth/* 엔드포인트 구현
- [ ] 서버: JWT 발급/검증 미들웨어
- [ ] 서버: OAuth provider별 토큰 교환 로직
- [ ] 서버: users 테이블 (provider, provider_id 컬럼 포함)
