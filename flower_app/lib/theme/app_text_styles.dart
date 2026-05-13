import 'package:flutter/material.dart';

/// 앱 전체 텍스트 스타일 정의
/// 디자인 담당자가 이 파일만 수정하면 됩니다.
class AppTextStyles {
  AppTextStyles._();

  // ── 앱 타이틀 (로고) ─────────────────────
  static const appTitle = TextStyle(
    fontSize: 36,
    fontWeight: FontWeight.bold,
    letterSpacing: 2,
    // color는 각 화면에서 계절 색상으로 덮어씁니다.
  );

  // ── 서브 타이틀 ──────────────────────────
  static const appSubtitle = TextStyle(
    fontSize: 14,
    fontWeight: FontWeight.normal,
  );

  // ── 상단 바 사용자 이름 ───────────────────
  static const topBarUsername = TextStyle(
    fontSize: 13,
    fontWeight: FontWeight.bold,
  );

  static const topBarSubtitle = TextStyle(
    fontSize: 11,
    color: Color(0xFF9E9E9E),
  );

  // ── 버튼 텍스트 ──────────────────────────
  static const buttonLarge = TextStyle(
    fontSize: 16,
    fontWeight: FontWeight.bold,
  );

  static const buttonMedium = TextStyle(
    fontSize: 15,
    fontWeight: FontWeight.w600,
  );

  // ── 입력 필드 힌트 ────────────────────────
  static const inputHint = TextStyle(
    fontSize: 14,
  );

  // ── 링크 / 보조 텍스트 ───────────────────
  static const link = TextStyle(
    fontSize: 14,
    fontWeight: FontWeight.bold,
  );

  static const caption = TextStyle(
    fontSize: 13,
    fontWeight: FontWeight.normal,
  );

  // ── 메뉴 버튼 라벨 ───────────────────────
  static const menuLabel = TextStyle(
    fontSize: 10,
    fontWeight: FontWeight.w600,
  );

  // ── 화면 제목 (AppBar) ────────────────────
  static const screenTitle = TextStyle(
    fontSize: 18,
    fontWeight: FontWeight.bold,
  );

  // ── 플레이스홀더 화면 타이틀 ──────────────
  static const placeholderTitle = TextStyle(
    fontSize: 22,
    fontWeight: FontWeight.bold,
  );

  static const placeholderBody = TextStyle(
    fontSize: 14,
  );
}
