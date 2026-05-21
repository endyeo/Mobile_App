import '../widgets/chat_floating_button.dart';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:pedometer/pedometer.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/step_notification_service.dart';
import '../services/walk_api_service.dart';

class PedometerScreen extends StatefulWidget {
  const PedometerScreen({super.key});

  @override
  State<PedometerScreen> createState() => _PedometerScreenState();
}

class _PedometerScreenState extends State<PedometerScreen>
    with SingleTickerProviderStateMixin {
  int _steps = 0;
  final int _goalSteps = 10000;
  int _pointBalance = 0;
  List<WalkRecord> _weeklyData = [];
  List<PointHistory> _pointHistory = [];
  bool _isLoading = true;
  bool _isSensorReady = false;
  String? _sensorMessage;
  DateTime? _lastSyncAt;
  StreamSubscription<StepCount>? _stepSubscription;
  StreamSubscription<PedestrianStatus>? _statusSubscription;

  late AnimationController _animController;
  late Animation<double> _progressAnim;

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    );
    _progressAnim = Tween<double>(begin: 0, end: 0).animate(
      CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic),
    );
    _loadData();
  }

  @override
  void dispose() {
    _stepSubscription?.cancel();
    _statusSubscription?.cancel();
    _animController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString('accessToken') ?? '';
      final weekly = await WalkApiService.getWeeklyRecords(token);
      final points = await WalkApiService.getPointBalance(token);
      if (!mounted) return;
      final todaySteps = weekly.isNotEmpty ? weekly.last.stepCount : 0;
      setState(() {
        _weeklyData = weekly;
        _pointBalance = points;
        _steps = todaySteps;
        _pointHistory = [];
        _isLoading = false;
        _updateProgressAnimation(0, _steps);
      });
      _animController.forward();
      await StepNotificationService.showSteps(
        steps: _steps,
        goalSteps: _goalSteps,
      );
      await _startPedometer();
    } catch (e) {
      debugPrint('[Walk] 데이터 로드 실패: $e');
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _sensorMessage = '서버 기록을 불러오지 못했습니다. 기기 걸음 수를 확인합니다.';
      });
      await _startPedometer();
    }
  }

  Future<void> _startPedometer() async {
    try {
      final PermissionStatus activityStatus = await Permission
          .activityRecognition
          .request();
      final PermissionStatus notificationStatus = await Permission.notification
          .request();

      if (!activityStatus.isGranted) {
        if (!mounted) return;
        setState(() {
          _isSensorReady = false;
          _sensorMessage = '걸음 수 권한이 필요합니다.';
        });
        return;
      }

      setState(() {
        _isSensorReady = true;
        _sensorMessage = notificationStatus.isGranted
            ? null
            : '알림 권한이 없어 알림 패널 표시가 제한될 수 있습니다.';
      });

      _stepSubscription?.cancel();
      _stepSubscription = Pedometer.stepCountStream.listen(
        _handleStepCount,
        onError: _handlePedometerError,
        cancelOnError: false,
      );

      _statusSubscription?.cancel();
      _statusSubscription = Pedometer.pedestrianStatusStream.listen(
        (_) {},
        onError: (_) {},
        cancelOnError: false,
      );
    } catch (e) {
      debugPrint('[Walk] 만보기 시작 실패: $e');
      if (mounted) {
        setState(() {
          _isSensorReady = false;
          _sensorMessage = '이 기기에서 걸음 수 센서를 사용할 수 없습니다.';
        });
      }
    }
  }

  Future<void> _handleStepCount(StepCount event) async {
    final int nextSteps = await _stepsForToday(event.steps);
    if (!mounted) return;
    if (nextSteps == _steps) return;
    final int previous = _steps;
    setState(() {
      _steps = nextSteps;
      _pointBalance = (_pointBalance < (_steps / 100).floor())
          ? (_steps / 100).floor()
          : _pointBalance;
      _updateProgressAnimation(previous, _steps);
      _weeklyData = _mergeTodayIntoWeeklyData(_weeklyData, _steps);
    });
    _animController
      ..reset()
      ..forward();
    await StepNotificationService.showSteps(
      steps: _steps,
      goalSteps: _goalSteps,
    );
    await _syncStepsIfNeeded();
  }

  void _handlePedometerError(Object error) {
    debugPrint('[Walk] 센서 오류: $error');
    if (!mounted) return;
    setState(() {
      _isSensorReady = false;
      _sensorMessage = '걸음 수 센서 데이터를 가져오지 못했습니다.';
    });
  }

  Future<int> _stepsForToday(int rawSteps) async {
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    final DateTime now = DateTime.now();
    final String todayKey =
        '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
    final String? baselineDate = prefs.getString('stepBaselineDate');
    if (baselineDate != todayKey) {
      await prefs.setString('stepBaselineDate', todayKey);
      await prefs.setInt('stepBaselineValue', rawSteps);
      return 0;
    }

    final int baseline = prefs.getInt('stepBaselineValue') ?? rawSteps;
    if (rawSteps < baseline) {
      await prefs.setInt('stepBaselineValue', rawSteps);
      return 0;
    }
    return rawSteps - baseline;
  }

  Future<void> _syncStepsIfNeeded() async {
    final DateTime now = DateTime.now();
    if (_lastSyncAt != null && now.difference(_lastSyncAt!).inSeconds < 30) {
      return;
    }
    _lastSyncAt = now;
    try {
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      final String token = prefs.getString('accessToken') ?? '';
      if (token.isEmpty) return;
      await WalkApiService.syncSteps(token, _steps);
    } catch (e) {
      debugPrint('[Walk] 동기화 실패: $e');
    }
  }

  void _updateProgressAnimation(int previousSteps, int nextSteps) {
    _progressAnim =
        Tween<double>(
          begin: previousSteps / _goalSteps,
          end: nextSteps / _goalSteps,
        ).animate(
          CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic),
        );
  }

  List<WalkRecord> _mergeTodayIntoWeeklyData(
    List<WalkRecord> records,
    int steps,
  ) {
    final DateTime now = DateTime.now();
    final String today = '${now.month}/${now.day}';
    if (records.isEmpty) {
      return <WalkRecord>[WalkRecord(day: today, stepCount: steps)];
    }
    final List<WalkRecord> next = List<WalkRecord>.from(records);
    next[next.length - 1] = WalkRecord(
      day: next.last.day.isEmpty ? today : next.last.day,
      stepCount: steps,
    );
    return next;
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    return Scaffold(
      backgroundColor: colors.background,
      floatingActionButton: const ChatFloatingButton(),
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new, color: colors.primary, size: 18),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text(
          '만보기',
          style: TextStyle(color: colors.primary, fontWeight: FontWeight.bold),
        ),
        actions: [
          Container(
            margin: const EdgeInsets.only(right: 16),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: const Color(0xFFFFF3E0),
              borderRadius: BorderRadius.circular(20),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(
                  Icons.monetization_on,
                  color: Color(0xFFFF9800),
                  size: 18,
                ),
                const SizedBox(width: 4),
                Text(
                  '$_pointBalance P',
                  style: const TextStyle(
                    color: Color(0xFFE65100),
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      body: _isLoading
          ? Center(child: CircularProgressIndicator(color: colors.primary))
          : SingleChildScrollView(
              padding: const EdgeInsets.all(20),
              child: Column(
                children: [
                  _buildCircularProgress(colors),
                  if (_sensorMessage != null) ...[
                    const SizedBox(height: 12),
                    _buildSensorMessage(colors),
                  ],
                  const SizedBox(height: 24),
                  _buildSensorStatus(colors),
                  const SizedBox(height: 28),
                  _buildWeeklyChart(colors),
                  const SizedBox(height: 28),
                  _buildPointHistory(colors),
                ],
              ),
            ),
    );
  }

  Widget _buildCircularProgress(SeasonColors colors) {
    return AnimatedBuilder(
      animation: _progressAnim,
      builder: (context, child) {
        final progress = _progressAnim.value.clamp(0.0, 1.0);
        return Container(
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(24),
            boxShadow: [
              BoxShadow(
                color: colors.primary.withAlpha(30),
                blurRadius: 20,
                offset: const Offset(0, 4),
              ),
            ],
          ),
          child: Column(
            children: [
              SizedBox(
                width: 200,
                height: 200,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    SizedBox(
                      width: 180,
                      height: 180,
                      child: CircularProgressIndicator(
                        value: progress,
                        strokeWidth: 12,
                        backgroundColor: Colors.grey.withAlpha(30),
                        valueColor: AlwaysStoppedAnimation(colors.primary),
                        strokeCap: StrokeCap.round,
                      ),
                    ),
                    Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.directions_walk,
                          color: colors.primary,
                          size: 32,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          '$_steps',
                          style: TextStyle(
                            fontSize: 36,
                            fontWeight: FontWeight.bold,
                            color: colors.primary,
                          ),
                        ),
                        Text(
                          '/ $_goalSteps 걸음',
                          style: TextStyle(
                            fontSize: 14,
                            color: Colors.grey[500],
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                children: [
                  _statItem('달성률', '${(progress * 100).toInt()}%', colors),
                  _statItem('적립 포인트', '+${(_steps / 100).floor()}P', colors),
                  _statItem(
                    '거리',
                    '${(_steps * 0.7 / 1000).toStringAsFixed(1)}km',
                    colors,
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _statItem(String label, String value, SeasonColors colors) {
    return Column(
      children: [
        Text(
          value,
          style: TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.bold,
            color: colors.primary,
          ),
        ),
        const SizedBox(height: 2),
        Text(label, style: TextStyle(fontSize: 12, color: Colors.grey[500])),
      ],
    );
  }

  Widget _buildSensorStatus(SeasonColors colors) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: _isSensorReady
            ? colors.primary.withAlpha(20)
            : Colors.orange.withAlpha(24),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            _isSensorReady ? Icons.sensors : Icons.sensors_off,
            color: _isSensorReady ? colors.primary : Colors.orange[700],
            size: 18,
          ),
          const SizedBox(width: 8),
          Text(
            _isSensorReady ? '기기 걸음 수를 측정 중입니다' : '걸음 수 센서를 준비 중입니다',
            style: TextStyle(
              color: _isSensorReady ? colors.primary : Colors.orange[800],
              fontWeight: FontWeight.w700,
              fontSize: 13,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSensorMessage(SeasonColors colors) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: colors.primary.withAlpha(35)),
      ),
      child: Text(
        _sensorMessage!,
        textAlign: TextAlign.center,
        style: TextStyle(
          color: Colors.grey[700],
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }

  Widget _buildWeeklyChart(SeasonColors colors) {
    if (_weeklyData.isEmpty) return const SizedBox.shrink();
    final maxSteps = _weeklyData
        .map((e) => e.stepCount)
        .reduce((a, b) => a > b ? a : b);
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(color: colors.primary.withAlpha(20), blurRadius: 10),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '주간 걸음 수',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.bold,
              color: colors.primary,
            ),
          ),
          const SizedBox(height: 16),
          SizedBox(
            height: 120,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              crossAxisAlignment: CrossAxisAlignment.end,
              children: _weeklyData.map((d) {
                final ratio = d.stepCount / maxSteps;
                final isToday = d == _weeklyData.last;
                return Column(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Text(
                      '${(d.stepCount / 1000).toStringAsFixed(1)}k',
                      style: TextStyle(fontSize: 9, color: Colors.grey[600]),
                    ),
                    const SizedBox(height: 4),
                    Container(
                      width: 28,
                      height: 80 * ratio,
                      decoration: BoxDecoration(
                        color: isToday
                            ? colors.primary
                            : colors.primary.withAlpha(80),
                        borderRadius: BorderRadius.circular(6),
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      d.day,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: isToday
                            ? FontWeight.bold
                            : FontWeight.normal,
                        color: isToday ? colors.primary : Colors.grey[600],
                      ),
                    ),
                  ],
                );
              }).toList(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPointHistory(SeasonColors colors) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(color: colors.primary.withAlpha(20), blurRadius: 10),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '포인트 내역',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.bold,
              color: colors.primary,
            ),
          ),
          const SizedBox(height: 12),
          ..._pointHistory.map((p) {
            final isPositive = p.amount > 0;
            IconData icon;
            switch (p.type) {
              case 'QUEST':
                icon = Icons.emoji_events;
                break;
              case 'SHOP':
                icon = Icons.shopping_bag;
                break;
              default:
                icon = Icons.directions_walk;
            }
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  Container(
                    width: 36,
                    height: 36,
                    decoration: BoxDecoration(
                      color: isPositive
                          ? colors.primary.withAlpha(25)
                          : const Color(0xFFFFEBEE),
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      icon,
                      size: 18,
                      color: isPositive ? colors.primary : Colors.red[400],
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          p.desc,
                          style: const TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                        Text(
                          p.time,
                          style: TextStyle(
                            fontSize: 11,
                            color: Colors.grey[500],
                          ),
                        ),
                      ],
                    ),
                  ),
                  Text(
                    '${isPositive ? "+" : ""}${p.amount}P',
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.bold,
                      color: isPositive ? colors.primary : Colors.red[400],
                    ),
                  ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }
}
