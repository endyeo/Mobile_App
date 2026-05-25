import 'package:flutter/material.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:permission_handler/permission_handler.dart';

import '../theme/season_theme.dart';

class PermissionSheet extends StatefulWidget {
  const PermissionSheet({super.key});

  static Future<void> show(BuildContext context) {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => const PermissionSheet(),
    );
  }

  @override
  State<PermissionSheet> createState() => _PermissionSheetState();
}

enum _EntryState { granted, denied, permanentlyDenied, restricted, limited }

typedef _StatusFetcher = Future<_EntryState> Function();
typedef _Handler = Future<void> Function();

_EntryState _fromPermission(PermissionStatus status) {
  if (status.isGranted) return _EntryState.granted;
  if (status.isPermanentlyDenied) return _EntryState.permanentlyDenied;
  if (status.isRestricted) return _EntryState.restricted;
  if (status.isLimited) return _EntryState.limited;
  return _EntryState.denied;
}

class _Entry {
  final String label;
  final String description;
  final IconData icon;
  final _StatusFetcher fetch;
  final _Handler handle;
  final String grantedLabel;
  final String deniedLabel;

  _Entry({
    required this.label,
    required this.description,
    required this.icon,
    required this.fetch,
    required this.handle,
    this.grantedLabel = '허용됨',
    this.deniedLabel = '미허용',
  });

  factory _Entry.standard(
    Permission permission, {
    required String label,
    required String description,
    required IconData icon,
  }) {
    return _Entry(
      label: label,
      description: description,
      icon: icon,
      fetch: () async => _fromPermission(await permission.status),
      handle: () async {
        final status = await permission.status;
        if (status.isPermanentlyDenied || status.isRestricted) {
          await openAppSettings();
        } else {
          await permission.request();
        }
      },
    );
  }
}

class _PermissionSheetState extends State<PermissionSheet> {
  late final List<_Entry> _entries = <_Entry>[
    _Entry.standard(
      Permission.activityRecognition,
      label: '걸음 수 측정',
      description: '만보기 화면과 산책 기록',
      icon: Icons.directions_walk,
    ),
    _Entry.standard(
      Permission.notification,
      label: '알림',
      description: '걸음 수 알림 패널 표시',
      icon: Icons.notifications_active_outlined,
    ),
    _Entry.standard(
      Permission.locationWhenInUse,
      label: '위치 (사용 중)',
      description: '지도·근처 꽃 검색에 필요',
      icon: Icons.location_on_outlined,
    ),
    _Entry(
      label: '위치 (항상 허용)',
      description: '백그라운드 꽃 근접 알림',
      icon: Icons.my_location,
      fetch: () async =>
          _fromPermission(await Permission.locationAlways.status),
      handle: _requestLocationAlways,
    ),
    _Entry(
      label: '배터리 최적화 예외',
      description: '만보기가 배터리 절약 모드에서 멈추지 않게',
      icon: Icons.battery_saver,
      fetch: _fetchBatteryStatus,
      handle: _requestBatteryException,
      grantedLabel: '예외 적용됨',
      deniedLabel: '기본',
    ),
    _Entry.standard(
      Permission.camera,
      label: '카메라',
      description: '꽃 사진 촬영 + 식물 식별',
      icon: Icons.camera_alt_outlined,
    ),
    _Entry.standard(
      Permission.photos,
      label: '사진',
      description: '게시글에 첨부할 사진 선택',
      icon: Icons.photo_library_outlined,
    ),
  ];

  Map<int, _EntryState> _states = const {};
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final result = <int, _EntryState>{};
    for (int i = 0; i < _entries.length; i++) {
      result[i] = await _entries[i].fetch();
    }
    if (!mounted) return;
    setState(() {
      _states = result;
      _loading = false;
    });
  }

  Future<void> _handleTap(int index) async {
    final entry = _entries[index];
    final state = _states[index];
    if (state == _EntryState.granted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('${entry.label}: 이미 설정되어 있습니다')));
      return;
    }
    await entry.handle();
    if (mounted) await _refresh();
  }

  static Future<void> _requestLocationAlways() async {
    final whenInUse = await Permission.locationWhenInUse.status;
    if (!whenInUse.isGranted) {
      final result = await Permission.locationWhenInUse.request();
      if (!result.isGranted) {
        if (result.isPermanentlyDenied) await openAppSettings();
        return;
      }
    }
    final always = await Permission.locationAlways.status;
    if (always.isPermanentlyDenied) {
      await openAppSettings();
    } else {
      await Permission.locationAlways.request();
    }
  }

  static Future<_EntryState> _fetchBatteryStatus() async {
    try {
      final ignoring =
          await FlutterForegroundTask.isIgnoringBatteryOptimizations;
      return ignoring ? _EntryState.granted : _EntryState.denied;
    } catch (_) {
      return _EntryState.denied;
    }
  }

  static Future<void> _requestBatteryException() async {
    try {
      await FlutterForegroundTask.requestIgnoreBatteryOptimization();
    } catch (_) {
      await openAppSettings();
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    final media = MediaQuery.of(context);

    return DraggableScrollableSheet(
      initialChildSize: 0.65,
      minChildSize: 0.4,
      maxChildSize: 0.9,
      expand: false,
      builder: (context, scrollController) => Container(
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
        ),
        padding: EdgeInsets.only(bottom: media.padding.bottom),
        child: Column(
          children: [
            const SizedBox(height: 12),
            Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: Colors.grey[300],
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 8),
              child: Row(
                children: [
                  Icon(Icons.shield_outlined, color: colors.primary),
                  const SizedBox(width: 8),
                  const Expanded(
                    child: Text(
                      '앱 권한',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: '새로 고침',
                    icon: const Icon(Icons.refresh, size: 20),
                    onPressed: _loading ? null : _refresh,
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Text(
                '항목을 탭하면 권한을 요청하거나 시스템 설정을 엽니다.',
                style: TextStyle(color: Colors.grey[600], fontSize: 12),
              ),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: _loading
                  ? Center(
                      child: CircularProgressIndicator(color: colors.primary),
                    )
                  : ListView.separated(
                      controller: scrollController,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 8,
                      ),
                      itemCount: _entries.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 6),
                      itemBuilder: (context, index) {
                        final entry = _entries[index];
                        final state = _states[index] ?? _EntryState.denied;
                        return _EntryTile(
                          entry: entry,
                          state: state,
                          colors: colors,
                          onTap: () => _handleTap(index),
                        );
                      },
                    ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
              child: SizedBox(
                width: double.infinity,
                height: 48,
                child: FilledButton.icon(
                  style: FilledButton.styleFrom(
                    backgroundColor: colors.primary,
                  ),
                  onPressed: () async {
                    await openAppSettings();
                  },
                  icon: const Icon(Icons.settings),
                  label: const Text('앱 설정 열기'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _EntryTile extends StatelessWidget {
  final _Entry entry;
  final _EntryState state;
  final SeasonColors colors;
  final VoidCallback onTap;

  const _EntryTile({
    required this.entry,
    required this.state,
    required this.colors,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final badge = _badge();
    return Material(
      color: Colors.grey[50],
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: colors.primary.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(entry.icon, color: colors.primary, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      entry.label,
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      entry.description,
                      style: TextStyle(fontSize: 11, color: Colors.grey[600]),
                    ),
                  ],
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 4,
                ),
                decoration: BoxDecoration(
                  color: badge.color.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(badge.icon, size: 12, color: badge.color),
                    const SizedBox(width: 4),
                    Text(
                      badge.label,
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        color: badge.color,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  _BadgeData _badge() {
    switch (state) {
      case _EntryState.granted:
        return _BadgeData(
          entry.grantedLabel,
          Colors.green[700]!,
          Icons.check_circle,
        );
      case _EntryState.permanentlyDenied:
        return _BadgeData('영구 거부', Colors.red[700]!, Icons.block);
      case _EntryState.restricted:
        return _BadgeData('제한됨', Colors.grey[700]!, Icons.lock);
      case _EntryState.limited:
        return _BadgeData('제한 허용', Colors.amber[800]!, Icons.warning_amber);
      case _EntryState.denied:
        return _BadgeData(
          entry.deniedLabel,
          Colors.orange[700]!,
          Icons.error_outline,
        );
    }
  }
}

class _BadgeData {
  final String label;
  final Color color;
  final IconData icon;
  const _BadgeData(this.label, this.color, this.icon);
}
