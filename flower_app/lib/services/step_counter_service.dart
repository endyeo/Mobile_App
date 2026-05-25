import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:pedometer/pedometer.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'step_notification_service.dart';
import 'walk_api_service.dart';

@pragma('vm:entry-point')
void _stepCounterTaskCallback() {
  FlutterForegroundTask.setTaskHandler(_StepCounterTaskHandler());
}

class _StepCounterTaskHandler extends TaskHandler {
  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {}

  @override
  void onRepeatEvent(DateTime timestamp) {}

  @override
  Future<void> onDestroy(DateTime timestamp) async {}
}

class StepCounterService {
  StepCounterService._();
  static final StepCounterService instance = StepCounterService._();

  static const String _kGoalKey = 'stepGoal';
  static const String _kLastKnownStepsKey = 'lastKnownTodaySteps';
  static const String _kBaselineDateKey = 'stepBaselineDate';
  static const String _kBaselineValueKey = 'stepBaselineValue';
  static const String _kBatteryHintShownKey = 'batteryOptHintShown';
  static const int _defaultGoal = 10000;
  static const Duration _syncInterval = Duration(seconds: 60);
  static const String _serviceId = 'ourt_step_counter';
  static const String _channelId = 'ourt_steps';
  static const String _channelName = 'OurT 걸음 수';

  final ValueNotifier<int> stepsNotifier = ValueNotifier<int>(0);
  final ValueNotifier<int> goalNotifier = ValueNotifier<int>(_defaultGoal);
  final ValueNotifier<bool> runningNotifier = ValueNotifier<bool>(false);
  final ValueNotifier<List<WalkRecord>> weeklyNotifier =
      ValueNotifier<List<WalkRecord>>(<WalkRecord>[]);
  final ValueNotifier<String?> errorNotifier = ValueNotifier<String?>(null);

  StreamSubscription<StepCount>? _stepSub;
  StreamSubscription<PedestrianStatus>? _statusSub;
  DateTime? _lastSyncAt;
  bool _initialized = false;
  bool _starting = false;

  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;
    final prefs = await SharedPreferences.getInstance();
    goalNotifier.value = prefs.getInt(_kGoalKey) ?? _defaultGoal;
    stepsNotifier.value = prefs.getInt(_kLastKnownStepsKey) ?? 0;
    FlutterForegroundTask.initCommunicationPort();
    FlutterForegroundTask.init(
      androidNotificationOptions: AndroidNotificationOptions(
        channelId: _channelId,
        channelName: _channelName,
        channelDescription: '현재 걸음 수를 알림 패널에 표시합니다.',
        channelImportance: NotificationChannelImportance.LOW,
        priority: NotificationPriority.LOW,
        onlyAlertOnce: true,
        showBadge: false,
      ),
      iosNotificationOptions: const IOSNotificationOptions(
        showNotification: false,
        playSound: false,
      ),
      foregroundTaskOptions: ForegroundTaskOptions(
        eventAction: ForegroundTaskEventAction.repeat(60000),
        autoRunOnBoot: false,
        autoRunOnMyPackageReplaced: true,
        allowWakeLock: true,
      ),
    );
  }

  Future<bool> setGoal(int goal) async {
    if (goal < 1000 || goal > 50000) return false;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_kGoalKey, goal);
    goalNotifier.value = goal;
    await _updateNotification();
    return true;
  }

  Future<void> refreshWeekly() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString('accessToken') ?? '';
    final weekly = await WalkApiService.getWeeklyRecords(token);
    weeklyNotifier.value = weekly;

    final todayIso = _todayIso();
    final serverToday = weekly
        .where((r) => r.recordDate == todayIso)
        .fold<int?>(null, (_, r) => r.stepCount);

    if (serverToday != null) {
      // 서버 값이 로컬보다 크면 (다른 기기에서 더 걸었을 수 있음) 채택
      if (serverToday > stepsNotifier.value) {
        stepsNotifier.value = serverToday;
      }
      // 다음 step 이벤트의 baseline 계산용 — 새 기기에서 처음 켰을 때
      // _stepsForToday가 이 값만큼 빼고 시작하게 됨
      await prefs.setInt(_kLastKnownStepsKey, stepsNotifier.value);
    }
  }

  String _todayIso() {
    final n = DateTime.now();
    return '${n.year.toString().padLeft(4, '0')}-${n.month.toString().padLeft(2, '0')}-${n.day.toString().padLeft(2, '0')}';
  }

  Future<bool> start() async {
    if (runningNotifier.value || _starting) return runningNotifier.value;
    _starting = true;
    try {
      await initialize();
      final activity = await Permission.activityRecognition.request();
      await Permission.notification.request();
      if (!activity.isGranted) {
        errorNotifier.value = activity.isPermanentlyDenied
            ? '걸음 수 권한이 영구적으로 거부되었습니다. 설정에서 직접 허용해주세요.'
            : '걸음 수 권한이 필요합니다.';
        return false;
      }
      errorNotifier.value = null;
      await refreshWeekly();

      _stepSub?.cancel();
      _stepSub = Pedometer.stepCountStream.listen(
        _handleStep,
        onError: _handleSensorError,
        cancelOnError: false,
      );
      _statusSub?.cancel();
      _statusSub = Pedometer.pedestrianStatusStream.listen(
        (_) {},
        onError: (_) {},
        cancelOnError: false,
      );

      await _startForegroundService();
      runningNotifier.value = true;
      await _updateNotification();
      return true;
    } finally {
      _starting = false;
    }
  }

  Future<bool> shouldShowBatteryHint() async {
    if (!runningNotifier.value) return false;
    try {
      final ignoring =
          await FlutterForegroundTask.isIgnoringBatteryOptimizations;
      if (ignoring) return false;
    } catch (_) {
      return false;
    }
    final prefs = await SharedPreferences.getInstance();
    return !(prefs.getBool(_kBatteryHintShownKey) ?? false);
  }

  Future<void> markBatteryHintShown() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_kBatteryHintShownKey, true);
  }

  Future<bool> requestIgnoreBatteryOptimization() async {
    try {
      return await FlutterForegroundTask.requestIgnoreBatteryOptimization();
    } catch (e) {
      debugPrint('[StepService] 배터리 최적화 예외 요청 실패: $e');
      return false;
    }
  }

  Future<void> stop() async {
    _stepSub?.cancel();
    _statusSub?.cancel();
    _stepSub = null;
    _statusSub = null;
    runningNotifier.value = false;
    try {
      if (await FlutterForegroundTask.isRunningService) {
        await FlutterForegroundTask.stopService();
      }
    } catch (e) {
      debugPrint('[StepService] stopService 실패: $e');
    }
    await StepNotificationService.cancel();
  }

  Future<void> _startForegroundService() async {
    try {
      if (await FlutterForegroundTask.isRunningService) return;
      await FlutterForegroundTask.startService(
        serviceId: _serviceId.hashCode,
        notificationTitle: '오늘 걸음 수',
        notificationText: _formatSteps(stepsNotifier.value, goalNotifier.value),
        callback: _stepCounterTaskCallback,
      );
    } catch (e) {
      debugPrint('[StepService] startService 실패: $e');
    }
  }

  Future<void> _handleStep(StepCount event) async {
    final int next = await _stepsForToday(event.steps);
    if (next == stepsNotifier.value) return;
    stepsNotifier.value = next;
    _mergeTodayIntoWeekly(next);
    await _updateNotification();
    await _syncIfNeeded();
  }

  void _handleSensorError(Object error) {
    debugPrint('[StepService] 센서 오류: $error');
    errorNotifier.value = '걸음 수 센서 데이터를 가져오지 못했습니다.';
  }

  Future<int> _stepsForToday(int rawSteps) async {
    final prefs = await SharedPreferences.getInstance();
    final now = DateTime.now();
    final todayKey =
        '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
    final baselineDate = prefs.getString(_kBaselineDateKey);

    if (baselineDate != todayKey) {
      await prefs.setString(_kBaselineDateKey, todayKey);
      // 서버에서 가져온 오늘 값이 있으면 그 위에서 이어 카운트
      // (예: 새 기기 로그인 → 서버 100보 → baseline = rawSteps - 100)
      final int alreadyToday = prefs.getInt(_kLastKnownStepsKey) ?? 0;
      final int recoveredBaseline = rawSteps - alreadyToday;
      if (recoveredBaseline >= 0 && alreadyToday > 0) {
        await prefs.setInt(_kBaselineValueKey, recoveredBaseline);
        return alreadyToday;
      }
      await prefs.setInt(_kBaselineValueKey, rawSteps);
      await prefs.setInt(_kLastKnownStepsKey, 0);
      return 0;
    }

    final baseline = prefs.getInt(_kBaselineValueKey) ?? rawSteps;

    if (rawSteps < baseline) {
      final lastKnown = prefs.getInt(_kLastKnownStepsKey) ?? 0;
      final recoveredBaseline = rawSteps - lastKnown;
      if (recoveredBaseline >= 0) {
        await prefs.setInt(_kBaselineValueKey, recoveredBaseline);
        return lastKnown;
      }
      await prefs.setInt(_kBaselineValueKey, rawSteps);
      return 0;
    }

    final todaySteps = rawSteps - baseline;
    await prefs.setInt(_kLastKnownStepsKey, todaySteps);
    return todaySteps;
  }

  Future<void> _syncIfNeeded({bool force = false}) async {
    final now = DateTime.now();
    if (!force &&
        _lastSyncAt != null &&
        now.difference(_lastSyncAt!) < _syncInterval) {
      return;
    }
    _lastSyncAt = now;
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString('accessToken') ?? '';
    if (token.isEmpty) {
      debugPrint('[StepService] sync skip: accessToken 비어있음');
      return;
    }
    final ok = await WalkApiService.syncSteps(token, stepsNotifier.value);
    debugPrint(
      '[StepService] sync ${ok ? "OK" : "FAIL"} steps=${stepsNotifier.value}',
    );
  }

  Future<void> _updateNotification() async {
    final text = _formatSteps(stepsNotifier.value, goalNotifier.value);
    try {
      if (await FlutterForegroundTask.isRunningService) {
        FlutterForegroundTask.updateService(
          notificationTitle: '오늘 걸음 수',
          notificationText: text,
        );
      } else {
        await StepNotificationService.showSteps(
          steps: stepsNotifier.value,
          goalSteps: goalNotifier.value,
        );
      }
    } catch (e) {
      debugPrint('[StepService] 알림 업데이트 실패: $e');
    }
  }

  void _mergeTodayIntoWeekly(int steps) {
    final now = DateTime.now();
    final today = '${now.month}/${now.day}';
    final todayIso = _todayIso();
    final current = List<WalkRecord>.from(weeklyNotifier.value);
    if (current.isEmpty) {
      weeklyNotifier.value = <WalkRecord>[
        WalkRecord(day: today, recordDate: todayIso, stepCount: steps),
      ];
      return;
    }
    final last = current.last;
    if (last.recordDate == todayIso || last.recordDate.isEmpty) {
      current[current.length - 1] = WalkRecord(
        day: today,
        recordDate: todayIso,
        stepCount: steps,
      );
    } else {
      current.add(
        WalkRecord(day: today, recordDate: todayIso, stepCount: steps),
      );
    }
    weeklyNotifier.value = current;
  }

  String _formatSteps(int steps, int goal) {
    return '${_withComma(steps)} / ${_withComma(goal)} 걸음';
  }

  String _withComma(int value) {
    final text = value.toString();
    final buffer = StringBuffer();
    for (int i = 0; i < text.length; i++) {
      final remaining = text.length - i;
      buffer.write(text[i]);
      if (remaining > 1 && remaining % 3 == 1) buffer.write(',');
    }
    return buffer.toString();
  }
}
