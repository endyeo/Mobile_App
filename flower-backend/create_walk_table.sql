-- 사용자별 일일 걸음 수 기록 — Flutter 만보기 화면이 30초마다 sync.
-- (user_id, record_date) UNIQUE → 같은 날은 upsert.

CREATE TABLE IF NOT EXISTS walk_records (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    record_date  DATE NOT NULL,
    step_count   INTEGER NOT NULL DEFAULT 0 CHECK (step_count >= 0),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, record_date)
);

CREATE INDEX IF NOT EXISTS idx_walk_records_user_date
    ON walk_records(user_id, record_date DESC);
