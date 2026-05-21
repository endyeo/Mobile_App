import 'package:flutter_local_notifications/flutter_local_notifications.dart';

class StepNotificationService {
  StepNotificationService._();

  static final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();

  static const int _notificationId = 1001;
  static const String _channelId = 'ourt_steps';
  static const String _channelName = 'OurT 걸음 수';
  static bool _initialized = false;

  static Future<void> initialize() async {
    if (_initialized) return;
    const AndroidInitializationSettings android = AndroidInitializationSettings(
      '@mipmap/ic_launcher',
    );
    await _plugin.initialize(const InitializationSettings(android: android));

    final AndroidFlutterLocalNotificationsPlugin? androidPlugin = _plugin
        .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin
        >();
    await androidPlugin?.createNotificationChannel(
      const AndroidNotificationChannel(
        _channelId,
        _channelName,
        description: '현재 걸음 수를 알림 패널에 표시합니다.',
        importance: Importance.low,
      ),
    );
    _initialized = true;
  }

  static Future<void> showSteps({
    required int steps,
    required int goalSteps,
  }) async {
    await initialize();
    final String formattedSteps = _formatNumber(steps);
    final String formattedGoal = _formatNumber(goalSteps);
    await _plugin.show(
      _notificationId,
      '오늘 걸음 수',
      '$formattedSteps / $formattedGoal 걸음',
      const NotificationDetails(
        android: AndroidNotificationDetails(
          _channelId,
          _channelName,
          channelDescription: '현재 걸음 수를 알림 패널에 표시합니다.',
          importance: Importance.low,
          priority: Priority.low,
          ongoing: true,
          onlyAlertOnce: true,
          showWhen: false,
        ),
      ),
    );
  }

  static Future<void> cancel() async {
    await _plugin.cancel(_notificationId);
  }

  static String _formatNumber(int value) {
    final String text = value.toString();
    final StringBuffer buffer = StringBuffer();
    for (int i = 0; i < text.length; i++) {
      final int remaining = text.length - i;
      buffer.write(text[i]);
      if (remaining > 1 && remaining % 3 == 1) {
        buffer.write(',');
      }
    }
    return buffer.toString();
  }
}
