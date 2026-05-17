import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 항상 허용 안내 다이얼로그
/// [firstTime] true면 첫 로그인용 (한 번만 표시), false면 GPS 사용 시 재안내
Future<void> promptAlwaysLocation(
  BuildContext context, {
  bool firstTime = false,
}) async {
  final permission = await Geolocator.checkPermission();
  if (permission == LocationPermission.always) return;
  if (permission == LocationPermission.deniedForever) return;
  if (permission == LocationPermission.denied) return;

  final prefs = await SharedPreferences.getInstance();
  if (firstTime && prefs.getBool('alwaysLocationAsked') == true) return;

  if (!context.mounted) return;

  final agreed = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      title: const Text(
        '근처 꽃 알림 받기 🌸',
        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
      ),
      content: const Text(
        '내 주변에 새 꽃 게시글이 올라오면 알림을 드려요.\n\n'
        '위치 권한을 \'항상 허용\'으로 설정하면 앱을 닫아도 알림을 받을 수 있어요.\n\n'
        '설정 → 위치 → 항상 허용',
        style: TextStyle(fontSize: 14, height: 1.5),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx, false),
          child: Text('나중에', style: TextStyle(color: Colors.grey[600])),
        ),
        ElevatedButton(
          onPressed: () => Navigator.pop(ctx, true),
          child: const Text('설정으로 이동'),
        ),
      ],
    ),
  );

  if (firstTime) {
    await prefs.setBool('alwaysLocationAsked', true);
  }

  if (agreed == true) await Geolocator.openAppSettings();
}
