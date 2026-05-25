import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import '../theme/season_theme.dart';
import '../services/step_counter_service.dart';
import '../services/walk_api_service.dart';
import '../widgets/chat_floating_button.dart';

class PedometerScreen extends StatefulWidget {
  const PedometerScreen({super.key});

  @override
  State<PedometerScreen> createState() => _PedometerScreenState();
}

class _PedometerScreenState extends State<PedometerScreen>
    with SingleTickerProviderStateMixin {
  final StepCounterService _service = StepCounterService.instance;

  late AnimationController _animController;
  Animation<double> _progressAnim = const AlwaysStoppedAnimation(0);
  int _lastSteps = 0;
  bool _initializing = true;

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    );
    _service.stepsNotifier.addListener(_onStepsChanged);
    _bootstrap();
  }

  @override
  void dispose() {
    _service.stepsNotifier.removeListener(_onStepsChanged);
    _animController.dispose();
    super.dispose();
  }

  Future<void> _bootstrap() async {
    await _service.initialize();
    _lastSteps = _service.stepsNotifier.value;
    if (!mounted) return;
    setState(() {
      _progressAnim = _buildAnim(0, _lastSteps);
      _initializing = false;
    });
    _animController.forward();

    if (_service.runningNotifier.value) {
      await _service.refreshWeekly();
    } else {
      await _service.start();
    }

    if (mounted && await _service.shouldShowBatteryHint()) {
      await _service.markBatteryHintShown();
      if (mounted) await _showBatteryHint();
    }
  }

  Future<void> _showBatteryHint() async {
    final colors = SeasonTheme.getColors();
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('백그라운드 카운트 안내'),
        content: const Text(
          '걸음 수를 끊김 없이 측정하려면 OurT가 배터리 절약 모드에서 잠들지 않게 허용해야 해요.\n\n'
          '"허용하기"를 누르면 시스템 설정 화면이 떠요.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('나중에'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: colors.primary),
            onPressed: () async {
              Navigator.pop(ctx);
              await _service.requestIgnoreBatteryOptimization();
            },
            child: const Text('허용하기'),
          ),
        ],
      ),
    );
  }

  void _onStepsChanged() {
    final next = _service.stepsNotifier.value;
    if (next == _lastSteps) return;
    if (!mounted) {
      _lastSteps = next;
      return;
    }
    setState(() {
      _progressAnim = _buildAnim(_lastSteps, next);
      _lastSteps = next;
    });
    _animController
      ..reset()
      ..forward();
  }

  Animation<double> _buildAnim(int from, int to) {
    final goal = _service.goalNotifier.value;
    return Tween<double>(begin: from / goal, end: to / goal).animate(
      CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic),
    );
  }

  Future<void> _showGoalDialog() async {
    final colors = SeasonTheme.getColors();
    final controller = TextEditingController(
      text: _service.goalNotifier.value.toString(),
    );
    final int? result = await showDialog<int>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('목표 걸음 수 설정'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: controller,
              keyboardType: TextInputType.number,
              autofocus: true,
              decoration: const InputDecoration(
                suffixText: '걸음',
                hintText: '1,000 ~ 50,000',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '권장: 성인 하루 8,000~10,000보',
              style: TextStyle(fontSize: 12, color: Colors.grey[600]),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('취소'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: colors.primary),
            onPressed: () {
              final raw = controller.text.replaceAll(',', '').trim();
              final v = int.tryParse(raw);
              if (v != null && v >= 1000 && v <= 50000) {
                Navigator.pop(ctx, v);
              } else {
                ScaffoldMessenger.of(ctx).showSnackBar(
                  const SnackBar(content: Text('1,000 ~ 50,000 사이로 입력해주세요')),
                );
              }
            },
            child: const Text('저장'),
          ),
        ],
      ),
    );
    if (result == null || !mounted) return;
    await _service.setGoal(result);
    setState(() {
      _progressAnim = _buildAnim(_lastSteps, _lastSteps);
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    if (_initializing) {
      return Scaffold(
        backgroundColor: colors.background,
        body: Center(child: CircularProgressIndicator(color: colors.primary)),
      );
    }
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
          IconButton(
            tooltip: '목표 걸음 수 설정',
            icon: Icon(Icons.tune, color: colors.primary),
            onPressed: _showGoalDialog,
          ),
        ],
      ),
      body: ListenableBuilder(
        listenable: Listenable.merge([
          _service.goalNotifier,
          _service.weeklyNotifier,
          _service.runningNotifier,
          _service.errorNotifier,
        ]),
        builder: (context, _) {
          final goal = _service.goalNotifier.value;
          final weekly = _service.weeklyNotifier.value;
          final running = _service.runningNotifier.value;
          final error = _service.errorNotifier.value;
          return SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: Column(
              children: [
                _buildCircularProgress(colors, goal),
                if (error != null) ...[
                  const SizedBox(height: 12),
                  _buildErrorMessage(colors, error),
                ],
                const SizedBox(height: 24),
                _buildSensorStatus(colors, running),
                const SizedBox(height: 28),
                _buildWeeklyChart(colors, weekly),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildCircularProgress(SeasonColors colors, int goal) {
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
                          '$_lastSteps',
                          style: TextStyle(
                            fontSize: 36,
                            fontWeight: FontWeight.bold,
                            color: colors.primary,
                          ),
                        ),
                        Text(
                          '/ $goal 걸음',
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
                  _statItem(
                    '거리',
                    '${(_lastSteps * 0.7 / 1000).toStringAsFixed(1)}km',
                    colors,
                  ),
                  _statItem(
                    '남은 걸음',
                    _lastSteps >= goal ? '달성!' : '${goal - _lastSteps}',
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

  Widget _buildSensorStatus(SeasonColors colors, bool running) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: running
            ? colors.primary.withAlpha(20)
            : Colors.orange.withAlpha(24),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            running ? Icons.sensors : Icons.sensors_off,
            color: running ? colors.primary : Colors.orange[700],
            size: 18,
          ),
          const SizedBox(width: 8),
          Text(
            running ? '백그라운드에서 걸음 수를 측정 중입니다' : '걸음 수 센서를 준비 중입니다',
            style: TextStyle(
              color: running ? colors.primary : Colors.orange[800],
              fontWeight: FontWeight.w700,
              fontSize: 13,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildErrorMessage(SeasonColors colors, String message) {
    final bool needsSettings = message.contains('권한');
    final bool permanent = message.contains('영구');
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: colors.primary.withAlpha(35)),
      ),
      child: Column(
        children: [
          Text(
            message,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: Colors.grey[700],
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
          if (needsSettings) ...[
            const SizedBox(height: 10),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (!permanent) ...[
                  TextButton.icon(
                    onPressed: () => _service.start(),
                    icon: const Icon(Icons.refresh, size: 16),
                    label: const Text('다시 요청'),
                  ),
                  const SizedBox(width: 4),
                ],
                FilledButton.icon(
                  style: FilledButton.styleFrom(
                    backgroundColor: colors.primary,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 6,
                    ),
                  ),
                  onPressed: () async {
                    await openAppSettings();
                  },
                  icon: const Icon(Icons.settings, size: 16),
                  label: const Text('앱 설정 열기'),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildWeeklyChart(SeasonColors colors, List<WalkRecord> weekly) {
    if (weekly.isEmpty) return const SizedBox.shrink();
    final maxSteps = weekly
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
              children: weekly.map((d) {
                final ratio = maxSteps == 0 ? 0.0 : d.stepCount / maxSteps;
                final isToday = d == weekly.last;
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
}
