import 'package:flutter/material.dart';

enum Season { spring, summer, autumn, winter }

class SeasonTheme {
  static Season getCurrentSeason() {
    final month = DateTime.now().month;
    if (month >= 3 && month <= 5) return Season.spring;
    if (month >= 6 && month <= 8) return Season.summer;
    if (month >= 9 && month <= 11) return Season.autumn;
    return Season.winter;
  }

  static SeasonColors getColors() {
    switch (getCurrentSeason()) {
      case Season.spring:
        return SeasonColors(
          primary: const Color(0xFFFF8FAB),      // 벚꽃 핑크
          secondary: const Color(0xFF98D9A4),    // 연두
          background: const Color(0xFFFFF0F5),   // 연한 핑크 화이트
          accent: const Color(0xFFFFB7C5),       // 연한 핑크
          buttonText: Colors.white,
          name: '봄',
        );
      case Season.summer:
        return SeasonColors(
          primary: const Color(0xFF2E86AB),      // 바다 파랑
          secondary: const Color(0xFF4CAF50),    // 초록
          background: const Color(0xFFF0F9FF),   // 연한 하늘색
          accent: const Color(0xFF81D4FA),       // 밝은 파랑
          buttonText: Colors.white,
          name: '여름',
        );
      case Season.autumn:
        return SeasonColors(
          primary: const Color(0xFFD4622A),      // 단풍 주황
          secondary: const Color(0xFFB5835A),    // 갈색
          background: const Color(0xFFFFF8F0),   // 따뜻한 크림
          accent: const Color(0xFFFFCC80),       // 노란 주황
          buttonText: Colors.white,
          name: '가을',
        );
      case Season.winter:
        return SeasonColors(
          primary: const Color(0xFF5C7FA3),      // 차분한 파랑
          secondary: const Color(0xFF90A4AE),    // 회청색
          background: const Color(0xFFF5F8FC),   // 눈처럼 밝은 흰색
          accent: const Color(0xFFB0BEC5),       // 연한 회색
          buttonText: Colors.white,
          name: '겨울',
        );
    }
  }
}

class SeasonColors {
  final Color primary;
  final Color secondary;
  final Color background;
  final Color accent;
  final Color buttonText;
  final String name;

  const SeasonColors({
    required this.primary,
    required this.secondary,
    required this.background,
    required this.accent,
    required this.buttonText,
    required this.name,
  });

  ThemeData toThemeData() {
    return ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: primary),
      primaryColor: primary,
      scaffoldBackgroundColor: background,
      useMaterial3: true,
    );
  }
}
