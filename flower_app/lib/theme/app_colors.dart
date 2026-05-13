import 'package:flutter/material.dart';

/// 앱 전체에서 사용하는 색상 정의
/// 디자인 담당자가 이 파일만 수정하면 됩니다.
class AppColors {
  AppColors._();

  // ── 봄 (3~5월) ──────────────────────────
  static const springPrimary   = Color(0xFFFF8FAB); // 벚꽃 핑크
  static const springSecondary = Color(0xFF98D9A4); // 연두
  static const springBg        = Color(0xFFFFF0F5); // 연한 핑크 화이트
  static const springAccent    = Color(0xFFFFB7C5); // 연한 핑크

  // ── 여름 (6~8월) ────────────────────────
  static const summerPrimary   = Color(0xFF2E86AB); // 바다 파랑
  static const summerSecondary = Color(0xFF4CAF50); // 초록
  static const summerBg        = Color(0xFFF0F9FF); // 연한 하늘색
  static const summerAccent    = Color(0xFF81D4FA); // 밝은 파랑

  // ── 가을 (9~11월) ───────────────────────
  static const autumnPrimary   = Color(0xFFD4622A); // 단풍 주황
  static const autumnSecondary = Color(0xFFB5835A); // 갈색
  static const autumnBg        = Color(0xFFFFF8F0); // 따뜻한 크림
  static const autumnAccent    = Color(0xFFFFCC80); // 노란 주황

  // ── 겨울 (12~2월) ───────────────────────
  static const winterPrimary   = Color(0xFF5C7FA3); // 차분한 파랑
  static const winterSecondary = Color(0xFF90A4AE); // 회청색
  static const winterBg        = Color(0xFFF5F8FC); // 눈처럼 밝은 흰색
  static const winterAccent    = Color(0xFFB0BEC5); // 연한 회색

  // ── 공통 고정 색상 ───────────────────────
  static const white        = Color(0xFFFFFFFF);
  static const black        = Color(0xFF000000);
  static const grey100      = Color(0xFFF5F5F5);
  static const grey300      = Color(0xFFE0E0E0);
  static const grey500      = Color(0xFF9E9E9E);
  static const grey700      = Color(0xFF616161);

  static const kakaoYellow  = Color(0xFFFEE500); // 카카오 버튼
  static const kakaoText    = Color(0xFF3C1E1E);

  static const mapBg        = Color(0xFFE8F4E8); // 지도 배경
}
