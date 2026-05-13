/// 앱 전체 여백, 크기, 모서리 반경 정의
/// 디자인 담당자가 이 파일만 수정하면 됩니다.
class AppDimensions {
  AppDimensions._();

  // ── 여백 (Padding / Margin) ───────────────
  static const double paddingXs  = 4.0;
  static const double paddingSm  = 8.0;
  static const double paddingMd  = 16.0;
  static const double paddingLg  = 24.0;
  static const double paddingXl  = 32.0;
  static const double paddingXxl = 48.0;

  // ── 모서리 반경 (Border Radius) ───────────
  static const double radiusSm   = 8.0;
  static const double radiusMd   = 14.0;
  static const double radiusLg   = 20.0;
  static const double radiusFull = 999.0; // 완전한 원형

  // ── 버튼 크기 ─────────────────────────────
  static const double buttonHeight    = 52.0;
  static const double buttonHeightSm  = 44.0;

  // ── 아이콘 크기 ───────────────────────────
  static const double iconSm   = 18.0;
  static const double iconMd   = 24.0;
  static const double iconLg   = 32.0;
  static const double iconXl   = 50.0;

  // ── 프로필/아바타 ─────────────────────────
  static const double avatarRadiusTopBar  = 18.0;
  static const double avatarRadiusLarge   = 45.0;

  // ── 플로팅 버튼 ───────────────────────────
  static const double fabMain   = 72.0;  // 중앙 챗봇 버튼 지름
  static const double fabSub    = 52.0;  // 서브 메뉴 버튼 지름

  // ── 플로팅 서브 버튼 위치 오프셋 ─────────
  static const double fabOffsetX = 95.0;
  static const double fabOffsetY = 80.0;

  // ── 하단 플로팅 메뉴 영역 높이 ───────────
  static const double floatingMenuHeight = 230.0;

  // ── 그림자 ────────────────────────────────
  static const double shadowBlurSm  = 8.0;
  static const double shadowBlurMd  = 12.0;
  static const double shadowBlurLg  = 20.0;
  static const double shadowSpreadSm = 1.0;
  static const double shadowSpreadMd = 4.0;
}
