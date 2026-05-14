-- ================================================================
-- 꽃 지도 게시판 마이그레이션 (Supabase SQL Editor에서 실행)
-- ================================================================

-- ── community_posts 컬럼 추가 ─────────────────────────────────
ALTER TABLE community_posts
    ADD COLUMN IF NOT EXISTS post_type         VARCHAR(20)  NOT NULL DEFAULT 'GENERAL',
    -- GENERAL: 기존 일반 게시글, FLOWER_SPOT: 꽃 지도 게시글
    ADD COLUMN IF NOT EXISTS plant_name        VARCHAR(100),
    -- Plant.id 인식 결과 꽃 이름, 실패 시 '기타'
    ADD COLUMN IF NOT EXISTS plant_confidence  FLOAT,
    -- Plant.id 인식 신뢰도 (0.0 ~ 1.0)
    ADD COLUMN IF NOT EXISTS notify_others     BOOLEAN      NOT NULL DEFAULT false;
    -- 이 게시글로 근처 사용자에게 알림 발송 여부

-- content 컬럼을 nullable로 변경 (FLOWER_SPOT은 텍스트 선택)
ALTER TABLE community_posts
    ALTER COLUMN content DROP NOT NULL;

-- post_type 인덱스 추가
CREATE INDEX IF NOT EXISTS idx_community_posts_type
    ON community_posts(post_type);

-- GPS 있는 FLOWER_SPOT만 빠르게 조회하기 위한 복합 인덱스
CREATE INDEX IF NOT EXISTS idx_flower_spots_location
    ON community_posts(post_type, latitude, longitude)
    WHERE post_type = 'FLOWER_SPOT' AND latitude IS NOT NULL;

-- ── users 테이블에 알림 수신 설정 추가 ──────────────────────
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS receive_notifications BOOLEAN NOT NULL DEFAULT true;
