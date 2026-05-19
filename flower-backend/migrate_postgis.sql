-- PostGIS 마이그레이션: community_posts와 users에 location 컬럼 추가
-- Supabase는 PostGIS가 기본 활성화되어 있으나 명시적으로 보장
CREATE EXTENSION IF NOT EXISTS postgis;

-- ─── community_posts ──────────────────────────────────────────
ALTER TABLE community_posts
    ADD COLUMN IF NOT EXISTS location geography(POINT, 4326);

-- 기존 데이터 마이그레이션 (위경도가 있는 행만)
UPDATE community_posts
   SET location = ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
 WHERE latitude IS NOT NULL
   AND longitude IS NOT NULL
   AND location IS NULL;

-- 공간 인덱스 (GIST)
CREATE INDEX IF NOT EXISTS idx_community_posts_location
    ON community_posts USING GIST(location);

-- 트리거: latitude/longitude 변경 시 location 자동 동기화
CREATE OR REPLACE FUNCTION sync_community_post_location()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL THEN
        NEW.location = ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
    ELSE
        NEW.location = NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_community_posts_location_sync ON community_posts;
CREATE TRIGGER trg_community_posts_location_sync
    BEFORE INSERT OR UPDATE OF latitude, longitude ON community_posts
    FOR EACH ROW EXECUTE FUNCTION sync_community_post_location();

-- ─── users ────────────────────────────────────────────────────
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS last_location geography(POINT, 4326);

UPDATE users
   SET last_location = ST_SetSRID(ST_MakePoint(last_longitude, last_latitude), 4326)::geography
 WHERE last_latitude IS NOT NULL
   AND last_longitude IS NOT NULL
   AND last_location IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_last_location
    ON users USING GIST(last_location);

CREATE OR REPLACE FUNCTION sync_user_last_location()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.last_latitude IS NOT NULL AND NEW.last_longitude IS NOT NULL THEN
        NEW.last_location = ST_SetSRID(ST_MakePoint(NEW.last_longitude, NEW.last_latitude), 4326)::geography;
    ELSE
        NEW.last_location = NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_last_location_sync ON users;
CREATE TRIGGER trg_users_last_location_sync
    BEFORE INSERT OR UPDATE OF last_latitude, last_longitude ON users
    FOR EACH ROW EXECUTE FUNCTION sync_user_last_location();
