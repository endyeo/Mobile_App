import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../app_actions/app_action_runtime.dart';
import '../services/chatbot_service.dart';
import '../services/community_api_service.dart';
import '../theme/season_theme.dart';
import '../widgets/app_bottom_navigation.dart';
import '../widgets/chat_floating_button.dart';
import 'flower_book_page.dart';
import 'pedometer_screen.dart';
import 'saved_page.dart';

class MainScreen extends StatefulWidget {
  const MainScreen({super.key});

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> {
  static const _festivalAspectRatio = 1200 / 628;

  final PageController _festivalPageController = PageController(viewportFraction: 0.92);
  final TextEditingController _chatController = TextEditingController();
  final ChatbotService _chatbotService = ChatbotService();
  final String _chatSessionId = DateTime.now().microsecondsSinceEpoch.toString();
  List<CommunityPost> _posts = [];
  final List<_MainChatMessage> _chatMessages = [];
  bool _isLoadingPosts = true;
  bool _isChatRunning = false;
  int _festivalIndex = 0;
  String? _chatStatus;
  String _nickname = '사용자';
  String? _profileImageUrl;

  @override
  void initState() {
    super.initState();
    _loadPosts();
    _loadUserInfo();
  }

  Future<void> _loadUserInfo() async {
    final prefs = await SharedPreferences.getInstance();
    if (mounted) {
      setState(() {
        _nickname = prefs.getString('nickname') ?? '사용자';
        _profileImageUrl = prefs.getString('profileImageUrl');
      });
    }
  }

  @override
  void dispose() {
    _festivalPageController.dispose();
    _chatController.dispose();
    super.dispose();
  }

  Future<void> _loadPosts() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString('accessToken') ?? '';
    final posts = await CommunityApiService.getPosts(token);
    if (!mounted) return;
    setState(() {
      _posts = posts.take(5).toList();
      _isLoadingPosts = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();

    return Scaffold(
      backgroundColor: colors.background,
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _loadPosts,
          color: colors.primary,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 18),
            children: [
              _buildTopBar(colors),
              const SizedBox(height: 16),
              _buildMainChatComposer(colors),
              const SizedBox(height: 14),
              _buildShortcutButtons(colors),
              const SizedBox(height: 18),
              _buildFestivalSection(context, colors),
              const SizedBox(height: 20),
              _sectionTitle('올라온 게시물', colors),
              _buildPostPreviewStrip(colors),
              const SizedBox(height: 20),
              _sectionTitle('산책 요약', colors),
              _buildWalkSummary(colors),
            ],
          ),
        ),
      ),
      floatingActionButton: const ChatFloatingButton(),
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
      bottomNavigationBar: const AppBottomNavigation(currentTab: AppNavTab.home),
    );
  }

  Widget _buildTopBar(SeasonColors colors) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          CircleAvatar(
            radius: 18,
            backgroundColor: colors.primary.withValues(alpha: 0.15),
            backgroundImage: _profileImageUrl != null ? NetworkImage(_profileImageUrl!) : null,
            child: _profileImageUrl == null
                ? Icon(Icons.person, color: colors.primary, size: 20)
                : null,
          ),
          const SizedBox(width: 10),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_nickname, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
              Text('${colors.name} 탐험가', style: TextStyle(fontSize: 11, color: Colors.grey[500])),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildMainChatComposer(SeasonColors colors) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_chatStatus != null) ...[_buildChatStatus(colors), const SizedBox(height: 8)],
        _buildChatEntry(colors),
      ],
    );
  }

  Widget _buildChatEntry(SeasonColors colors) {
    return Container(
      height: 52,
      padding: const EdgeInsets.only(left: 14, right: 6),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: colors.primary.withValues(alpha: 0.14)),
        boxShadow: [BoxShadow(color: colors.primary.withValues(alpha: 0.10), blurRadius: 10, offset: const Offset(0, 3))],
      ),
      child: Row(
        children: [
          Icon(Icons.chat_bubble_outline, color: colors.primary, size: 22),
          const SizedBox(width: 10),
          Expanded(
            child: TextField(
              controller: _chatController,
              enabled: !_isChatRunning,
              textInputAction: TextInputAction.send,
              decoration: InputDecoration(
                hintText: _isChatRunning ? '챗봇이 작업 중입니다' : '챗봇에게 물어보기',
                border: InputBorder.none,
                isDense: true,
              ),
              onSubmitted: _sendMainChatMessage,
            ),
          ),
          IconButton(
            icon: Icon(Icons.send_rounded, color: colors.primary, size: 21),
            onPressed: _isChatRunning ? null : () => _sendMainChatMessage(_chatController.text),
          ),
        ],
      ),
    );
  }

  Widget _buildChatStatus(SeasonColors colors) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.86),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: colors.primary.withValues(alpha: 0.10)),
      ),
      child: Row(
        children: [
          SizedBox(
            width: 16, height: 16,
            child: CircularProgressIndicator(strokeWidth: 2, color: colors.primary),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(_chatStatus ?? '', maxLines: 1, overflow: TextOverflow.ellipsis,
              style: TextStyle(color: colors.primary, fontSize: 13, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }

  Future<void> _sendMainChatMessage(String rawText) async {
    final text = rawText.trim();
    if (text.isEmpty || _isChatRunning) return;

    _chatController.clear();
    FocusScope.of(context).unfocus();

    setState(() {
      _chatMessages.add(_MainChatMessage.user(text));
      _isChatRunning = true;
      _chatStatus = '요청을 분석하는 중입니다';
    });

    Future.delayed(const Duration(milliseconds: 700), () {
      if (!mounted || !_isChatRunning) return;
      setState(() => _chatStatus = '챗봇이 답변을 준비하는 중입니다');
    });

    try {
      final response = await _chatbotService.sendMessage(
        message: text, sessionId: _chatSessionId, lat: 37.5665, lng: 126.9780,
      );
      if (!mounted) return;
      setState(() {
        _chatMessages.add(_MainChatMessage.bot(response.reply));
        _isChatRunning = false;
        _chatStatus = null;
      });
      if (mounted) await AppActionRuntime.execute(context, response.actions);
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _chatMessages.add(_MainChatMessage.bot('응답을 가져오지 못했습니다.'));
        _isChatRunning = false;
        _chatStatus = null;
      });
    }
  }

  Widget _buildShortcutButtons(SeasonColors colors) {
    final items = [
      _HomeMenuItem(Icons.menu_book_outlined, '도감', () => _goTo(context, const FlowerBookPage())),
      _HomeMenuItem(Icons.directions_walk, '만보기', () => _goTo(context, const PedometerScreen())),
      _HomeMenuItem(Icons.bookmark_outline, '저장', () => _goTo(context, const SavedPage())),
    ];

    return Row(
      children: [
        for (final item in items) ...[
          Expanded(
            child: Tooltip(
              message: item.label,
              child: InkWell(
                borderRadius: BorderRadius.circular(16),
                onTap: item.onTap,
                child: Ink(
                  height: 62,
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: colors.primary.withValues(alpha: 0.12)),
                    boxShadow: [BoxShadow(color: colors.primary.withValues(alpha: 0.10), blurRadius: 10, offset: const Offset(0, 3))],
                  ),
                  child: Icon(item.icon, color: colors.primary, size: 28),
                ),
              ),
            ),
          ),
          if (item != items.last) const SizedBox(width: 10),
        ],
      ],
    );
  }

  Widget _buildFestivalSection(BuildContext context, SeasonColors colors) {
    final banners = [
      const _FestivalBanner(title: '이번 주 주변 꽃 축제', description: '근처에서 열리는 꽃 축제를 확인해보세요',
          colors: [Color(0xFFFFB7C5), Color(0xFFFFF0A6), Color(0xFF98D9A4)]),
      const _FestivalBanner(title: '주말 산책 추천', description: '지금 가기 좋은 꽃길을 찾아보세요',
          colors: [Color(0xFFBEE3F8), Color(0xFFC6F6D5), Color(0xFFFFE4E6)]),
      const _FestivalBanner(title: '인기 꽃 스팟', description: '사용자들이 많이 찾는 장소',
          colors: [Color(0xFFFBCFE8), Color(0xFFDDD6FE), Color(0xFFBFDBFE)]),
    ];
    final bannerHeight = (MediaQuery.sizeOf(context).width * 0.92) / _festivalAspectRatio;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          height: bannerHeight,
          child: PageView.builder(
            controller: _festivalPageController,
            padEnds: true,
            itemCount: banners.length,
            onPageChanged: (index) => setState(() => _festivalIndex = index),
            itemBuilder: (context, index) => _buildFestivalImage(banners[index]),
          ),
        ),
        const SizedBox(height: 10),
        _buildFestivalDescription(colors, banners[_festivalIndex]),
      ],
    );
  }

  Widget _buildFestivalImage(_FestivalBanner banner) {
    return Stack(
      fit: StackFit.expand,
      children: [
        DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: banner.colors),
          ),
        ),
        Positioned(
          right: 22, bottom: 14,
          child: Icon(Icons.local_florist, color: Colors.white.withValues(alpha: 0.78), size: 92),
        ),
      ],
    );
  }

  Widget _buildFestivalDescription(SeasonColors colors, _FestivalBanner banner) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
      color: Colors.white,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(banner.title, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
          const SizedBox(height: 4),
          Text(banner.description, style: TextStyle(fontSize: 12, color: Colors.grey[600])),
        ],
      ),
    );
  }

  Widget _sectionTitle(String text, SeasonColors colors) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Text(text, style: TextStyle(color: colors.primary, fontSize: 16, fontWeight: FontWeight.w800)),
    );
  }

  Widget _buildPostPreviewStrip(SeasonColors colors) {
    if (_isLoadingPosts) {
      return SizedBox(height: 96, child: Center(child: CircularProgressIndicator(color: colors.primary)));
    }
    if (_posts.isEmpty) {
      return Container(
        height: 86, alignment: Alignment.center,
        decoration: _panelDecoration(colors),
        child: Text('표시할 게시물이 없습니다', style: TextStyle(color: Colors.grey[600], fontSize: 13)),
      );
    }
    return SizedBox(
      height: 104,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: _posts.length,
        separatorBuilder: (context, index) => const SizedBox(width: 10),
        itemBuilder: (context, index) {
          final post = _posts[index];
          return Container(
            width: 132,
            decoration: _panelDecoration(colors),
            clipBehavior: Clip.antiAlias,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: post.imageUrl == null || post.imageUrl!.isEmpty
                      ? Container(width: double.infinity, color: colors.primary.withValues(alpha: 0.12),
                          child: Icon(Icons.article_outlined, color: colors.primary))
                      : Image.network(post.imageUrl!, width: double.infinity, fit: BoxFit.cover,
                          errorBuilder: (c, e, s) => Container(width: double.infinity,
                              color: colors.primary.withValues(alpha: 0.12),
                              child: Icon(Icons.article_outlined, color: colors.primary))),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(9, 6, 9, 7),
                  child: Text(post.content, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildWalkSummary(SeasonColors colors) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: _panelDecoration(colors),
      child: Row(
        children: [
          Icon(Icons.directions_walk, color: colors.primary, size: 28),
          const SizedBox(width: 12),
          const Expanded(child: Text('오늘 산책, 퀘스트, 포인트 요약 영역', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700))),
        ],
      ),
    );
  }

  BoxDecoration _panelDecoration(SeasonColors colors) {
    return BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(14),
      boxShadow: [BoxShadow(color: colors.primary.withValues(alpha: 0.10), blurRadius: 10, offset: const Offset(0, 3))],
    );
  }

  void _goTo(BuildContext context, Widget screen) {
    Navigator.push(context, MaterialPageRoute(builder: (_) => screen));
  }
}

class _HomeMenuItem {
  const _HomeMenuItem(this.icon, this.label, this.onTap);
  final IconData icon;
  final String label;
  final VoidCallback onTap;
}

class _FestivalBanner {
  const _FestivalBanner({required this.title, required this.description, required this.colors});
  final String title;
  final String description;
  final List<Color> colors;
}

class _MainChatMessage {
  const _MainChatMessage._({required this.text, required this.isUser});
  factory _MainChatMessage.user(String text) => _MainChatMessage._(text: text, isUser: true);
  factory _MainChatMessage.bot(String text) => _MainChatMessage._(text: text, isUser: false);
  final String text;
  final bool isUser;
}
