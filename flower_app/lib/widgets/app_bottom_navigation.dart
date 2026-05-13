import 'package:flutter/material.dart';

import '../screens/community_feed_screen.dart';
import '../screens/kakao_map_screen.dart';
import '../screens/main_screen.dart';
import '../screens/my_info_screen.dart';
import '../theme/season_theme.dart';

enum AppNavTab { map, home, community, myInfo }

class AppBottomNavigation extends StatelessWidget {
  const AppBottomNavigation({super.key, required this.currentTab});

  final AppNavTab currentTab;

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();

    return SafeArea(
      top: false,
      child: Container(
        height: 65,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        decoration: BoxDecoration(
          color: Colors.white,
          boxShadow: [
            BoxShadow(color: Colors.black.withValues(alpha: 0.08), blurRadius: 14, offset: const Offset(0, -3)),
          ],
        ),
        child: Row(
          children: [
            Expanded(child: _item(context: context, colors: colors, tab: AppNavTab.map,
                icon: Icons.map_outlined, label: '지도', screen: const KakaoMapScreen())),
            Expanded(child: _item(context: context, colors: colors, tab: AppNavTab.home,
                icon: Icons.home_rounded, label: '홈', screen: const MainScreen())),
            Expanded(child: _item(context: context, colors: colors, tab: AppNavTab.community,
                icon: Icons.people_alt_outlined, label: '커뮤니티', screen: const CommunityFeedScreen())),
            Expanded(child: _item(context: context, colors: colors, tab: AppNavTab.myInfo,
                icon: Icons.person_outline, label: '내정보', screen: const MyInfoScreen())),
          ],
        ),
      ),
    );
  }

  Widget _item({
    required BuildContext context, required SeasonColors colors,
    required AppNavTab tab, required IconData icon,
    required String label, required Widget screen,
  }) {
    final selected = currentTab == tab;
    final color = selected ? colors.primary : Colors.grey[500]!;

    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: selected ? null : () {
        // 스택 바닥(첫 화면)까지 pop 후 새 탭 push → 뒤로가기 시 검정 화면 방지
        Navigator.of(context).popUntil((route) => route.isFirst);
        if (tab != AppNavTab.home) {
          Navigator.of(context).push(MaterialPageRoute(builder: (_) => screen));
        }
      },
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, color: color, size: 23),
          const SizedBox(height: 2),
          Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
            style: TextStyle(color: color, fontSize: 11,
              fontWeight: selected ? FontWeight.w800 : FontWeight.w600)),
        ],
      ),
    );
  }
}
