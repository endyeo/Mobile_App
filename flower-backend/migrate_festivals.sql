-- Festival 캐시 테이블 — TourAPI 응답을 주 1회 동기화해서 보관.
-- 지도/홈/챗봇에서 외부 API 호출 없이 이 테이블만 조회.

CREATE TABLE IF NOT EXISTS festivals (
    id                BIGSERIAL PRIMARY KEY,
    content_id        VARCHAR(32) NOT NULL UNIQUE,
    title             VARCHAR(200) NOT NULL,
    addr1             VARCHAR(200),
    addr2             VARCHAR(200),
    map_y             DOUBLE PRECISION,
    map_x             DOUBLE PRECISION,
    first_image       VARCHAR(500),
    first_image2      VARCHAR(500),
    tel               VARCHAR(50),
    event_start_date  CHAR(8),
    event_end_date    CHAR(8),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_festivals_event_dates
    ON festivals(event_start_date, event_end_date);

-- 운영 적용 후 처음에는 비어있음 → FestivalCacheService가 앱 시작 시 한 번,
-- 그리고 주 1회 일요일 03:00에 갱신.
