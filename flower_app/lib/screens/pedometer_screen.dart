import '../widgets/chat_floating_button.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/walk_api_service.dart';

class PedometerScreen extends StatefulWidget {
  const PedometerScreen({super.key});

  @override
  State<PedometerScreen> createState() => _PedometerScreenState();
}

class _PedometerScreenState extends State<PedometerScreen> with SingleTickerProviderStateMixin {
  int _steps = 0;
  final int _goalSteps = 10000;
  int _pointBalance = 0;
  List<WalkRecord> _weeklyData = [];
  List<PointHistory> _pointHistory = [];
  bool _isLoading = true;

  late AnimationController _animController;
  late Animation<double> _progressAnim;

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200));
    _progressAnim = Tween<double>(begin: 0, end: 0)
        .animate(CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic));
    _loadData();
  }

  @override
  void dispose() {
    _animController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString('accessToken') ?? '';
      final weekly = await WalkApiService.getWeeklyRecords(token);
      final points = await WalkApiService.getPointBalance(token);
      if (mounted) {
        final todaySteps = weekly.isNotEmpty ? weekly.last.stepCount : 0;
        setState(() {
          _weeklyData = weekly;
          _pointBalance = points;
          _steps = todaySteps;
          _pointHistory = [];
          _isLoading = false;
          _progressAnim = Tween<double>(begin: 0, end: _steps / _goalSteps)
              .animate(CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic));
          _animController.forward();
        });
      }
    } catch (e) {
      debugPrint('[Walk] 데이터 로드 실패: $e');
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _simulateWalk() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString('accessToken') ?? '';
      setState(() {
        _steps += 150;
        _pointBalance += 1;
        _progressAnim = Tween<double>(begin: _progressAnim.value, end: _steps / _goalSteps)
            .animate(CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic));
        _animController..reset()..forward();
      });
      await WalkApiService.syncSteps(token, _steps);
    } catch (e) {
      debugPrint('[Walk] 동기화 실패: $e');
    }
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
        title: Text('만보기', style: TextStyle(color: colors.primary, fontWeight: FontWeight.bold)),
        actions: [
          Container(
            margin: const EdgeInsets.only(right: 16),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(color: const Color(0xFFFFF3E0), borderRadius: BorderRadius.circular(20)),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.monetization_on, color: Color(0xFFFF9800), size: 18),
                const SizedBox(width: 4),
                Text('$_pointBalance P', style: const TextStyle(color: Color(0xFFE65100), fontWeight: FontWeight.bold, fontSize: 14)),
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
                  const SizedBox(height: 24),
                  ElevatedButton.icon(
                    onPressed: _simulateWalk,
                    icon: const Icon(Icons.directions_walk),
                    label: const Text('걸음 시뮬레이션 (+150보)'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: colors.primary, foregroundColor: Colors.white,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                    ),
                  ),
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
            color: Colors.white, borderRadius: BorderRadius.circular(24),
            boxShadow: [BoxShadow(color: colors.primary.withAlpha(30), blurRadius: 20, offset: const Offset(0, 4))],
          ),
          child: Column(
            children: [
              SizedBox(
                width: 200, height: 200,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    SizedBox(
                      width: 180, height: 180,
                      child: CircularProgressIndicator(
                        value: progress, strokeWidth: 12,
                        backgroundColor: Colors.grey.withAlpha(30),
                        valueColor: AlwaysStoppedAnimation(colors.primary),
                        strokeCap: StrokeCap.round,
                      ),
                    ),
                    Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.directions_walk, color: colors.primary, size: 32),
                        const SizedBox(height: 4),
                        Text('$_steps', style: TextStyle(fontSize: 36, fontWeight: FontWeight.bold, color: colors.primary)),
                        Text('/ $_goalSteps 걸음', style: TextStyle(fontSize: 14, color: Colors.grey[500])),
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
                  _statItem('거리', '${(_steps * 0.7 / 1000).toStringAsFixed(1)}km', colors),
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
        Text(value, style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: colors.primary)),
        const SizedBox(height: 2),
        Text(label, style: TextStyle(fontSize: 12, color: Colors.grey[500])),
      ],
    );
  }

  Widget _buildWeeklyChart(SeasonColors colors) {
    if (_weeklyData.isEmpty) return const SizedBox.shrink();
    final maxSteps = _weeklyData.map((e) => e.stepCount).reduce((a, b) => a > b ? a : b);
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white, borderRadius: BorderRadius.circular(20),
        boxShadow: [BoxShadow(color: colors.primary.withAlpha(20), blurRadius: 10)],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('주간 걸음 수', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: colors.primary)),
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
                    Text('${(d.stepCount / 1000).toStringAsFixed(1)}k', style: TextStyle(fontSize: 9, color: Colors.grey[600])),
                    const SizedBox(height: 4),
                    Container(
                      width: 28, height: 80 * ratio,
                      decoration: BoxDecoration(
                        color: isToday ? colors.primary : colors.primary.withAlpha(80),
                        borderRadius: BorderRadius.circular(6),
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(d.day, style: TextStyle(
                      fontSize: 12,
                      fontWeight: isToday ? FontWeight.bold : FontWeight.normal,
                      color: isToday ? colors.primary : Colors.grey[600],
                    )),
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
        color: Colors.white, borderRadius: BorderRadius.circular(20),
        boxShadow: [BoxShadow(color: colors.primary.withAlpha(20), blurRadius: 10)],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('포인트 내역', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: colors.primary)),
          const SizedBox(height: 12),
          ..._pointHistory.map((p) {
            final isPositive = p.amount > 0;
            IconData icon;
            switch (p.type) {
              case 'QUEST': icon = Icons.emoji_events; break;
              case 'SHOP': icon = Icons.shopping_bag; break;
              default: icon = Icons.directions_walk;
            }
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  Container(
                    width: 36, height: 36,
                    decoration: BoxDecoration(
                      color: isPositive ? colors.primary.withAlpha(25) : const Color(0xFFFFEBEE),
                      shape: BoxShape.circle,
                    ),
                    child: Icon(icon, size: 18, color: isPositive ? colors.primary : Colors.red[400]),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(p.desc, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500)),
                        Text(p.time, style: TextStyle(fontSize: 11, color: Colors.grey[500])),
                      ],
                    ),
                  ),
                  Text(
                    '${isPositive ? "+" : ""}${p.amount}P',
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold, color: isPositive ? colors.primary : Colors.red[400]),
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
