import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../app_actions/app_action_runtime.dart';
import '../services/chatbot_service.dart';
import '../services/community_api_service.dart';
import 'community_feed_screen.dart';
import '../services/tour_api_service.dart';
import '../theme/season_theme.dart';
import '../widgets/app_bottom_navigation.dart';
import '../widgets/chat_floating_button.dart';
import 'flower_book_page.dart';
import 'kakao_map_screen.dart';
import 'pedometer_screen.dart';
import 'saved_page.dart';

class MainScreen extends StatefulWidget {
  const MainScreen({super.key});

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> {
  final TextEditingController _chatController = TextEditingController();
  final ChatbotService _chatbotService = ChatbotService();
  final TourApiService _tourApiService = TourApiService();
  final PageController _festivalPageController = PageController(
    viewportFraction: 0.92,
  );
  final String _chatSessionId = DateTime.now().microsecondsSinceEpoch
      .toString();
  final List<_MainChatMessage> _chatMessages = <_MainChatMessage>[];

  List<CommunityPost> _posts = <CommunityPost>[];
  List<FestivalData> _festivals = <FestivalData>[];
  bool _isLoadingPosts = true;
  bool _isLoadingFestivals = true;
  bool _isChatRunning = false;
  String? _chatStatus;
  String? _festivalError;
  String _nickname = '사용자';
  String? _profileImageUrl;
  int _currentFestivalPage = 0;

  @override
  void initState() {
    super.initState();
    _loadPosts();
    _loadFestivals();
    _loadUserInfo();
  }

  Future<void> _loadUserInfo() async {
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _nickname = prefs.getString('nickname') ?? '사용자';
      _profileImageUrl = prefs.getString('profileImageUrl');
    });
  }

  @override
  void dispose() {
    _chatController.dispose();
    _festivalPageController.dispose();
    super.dispose();
  }

  Future<void> _loadPosts() async {
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    final String token = prefs.getString('accessToken') ?? '';
    final posts = (await CommunityApiService.getPosts(token)).posts;
    if (!mounted) return;
    setState(() {
      _posts = posts.take(5).toList();
      _isLoadingPosts = false;
    });
  }

  Future<void> _loadFestivals() async {
    try {
      final List<FestivalData> festivals = await _tourApiService
          .getFlowerFestivals();
      if (!mounted) return;
      setState(() {
        _festivals = festivals.take(5).toList();
        _isLoadingFestivals = false;
        _festivalError = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isLoadingFestivals = false;
        _festivalError = error.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final SeasonColors colors = SeasonTheme.getColors();

    return Scaffold(
      backgroundColor: colors.background,
      body: SafeArea(
        child: RefreshIndicator(
          color: colors.primary,
          onRefresh: () async {
            await Future.wait(<Future<void>>[_loadPosts(), _loadFestivals()]);
          },
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 18),
            children: <Widget>[
              _buildTopBar(colors),
              const SizedBox(height: 16),
              _buildMainChatComposer(colors),
              const SizedBox(height: 14),
              _buildShortcutButtons(colors),
              const SizedBox(height: 18),
              _sectionTitle('축제 소식', colors),
              _buildFestivalSection(colors),
              const SizedBox(height: 20),
              _sectionTitle('꽃 게시글', colors),
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
      bottomNavigationBar: const AppBottomNavigation(
        currentTab: AppNavTab.home,
      ),
    );
  }

  Widget _buildTopBar(SeasonColors colors) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: <Widget>[
          CircleAvatar(
            radius: 18,
            backgroundColor: colors.primary.withValues(alpha: 0.15),
            backgroundImage: _profileImageUrl != null
                ? NetworkImage(_profileImageUrl!)
                : null,
            child: _profileImageUrl == null
                ? Icon(Icons.person, color: colors.primary, size: 20)
                : null,
          ),
          const SizedBox(width: 10),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Text(
                _nickname,
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                ),
              ),
              Text(
                '${colors.name} 산책 메이트',
                style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildMainChatComposer(SeasonColors colors) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        if (_chatStatus != null) ...<Widget>[
          _buildChatStatus(colors),
          const SizedBox(height: 8),
        ],
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
        boxShadow: <BoxShadow>[
          BoxShadow(
            color: colors.primary.withValues(alpha: 0.10),
            blurRadius: 10,
            offset: const Offset(0, 3),
          ),
        ],
      ),
      child: Row(
        children: <Widget>[
          Icon(Icons.chat_bubble_outline, color: colors.primary, size: 22),
          const SizedBox(width: 10),
          Expanded(
            child: TextField(
              controller: _chatController,
              enabled: !_isChatRunning,
              textInputAction: TextInputAction.send,
              decoration: InputDecoration(
                hintText: _isChatRunning
                    ? '챗봇이 답변을 준비하고 있어요'
                    : '챗봇에게 물어보세요',
                border: InputBorder.none,
                isDense: true,
              ),
              onSubmitted: _sendMainChatMessage,
            ),
          ),
          IconButton(
            icon: Icon(Icons.send_rounded, color: colors.primary, size: 21),
            onPressed: _isChatRunning
                ? null
                : () => _sendMainChatMessage(_chatController.text),
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
        children: <Widget>[
          SizedBox(
            width: 16,
            height: 16,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              color: colors.primary,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              _chatStatus ?? '',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: colors.primary,
                fontSize: 13,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _sendMainChatMessage(String rawText) async {
    final String text = rawText.trim();
    if (text.isEmpty || _isChatRunning) return;

    _chatController.clear();
    FocusScope.of(context).unfocus();

    setState(() {
      _chatMessages.add(_MainChatMessage.user(text));
      _isChatRunning = true;
      _chatStatus = '질문을 분석하고 있어요';
    });

    Future<void>.delayed(const Duration(milliseconds: 700), () {
      if (!mounted || !_isChatRunning) return;
      setState(() => _chatStatus = '답변을 준비하고 있어요');
    });

    try {
      final ChatbotResponse response = await _chatbotService.sendMessage(
        message: text,
        sessionId: _chatSessionId,
        lat: 37.5665,
        lng: 126.9780,
      );
      if (!mounted) return;
      setState(() {
        _chatMessages.add(_MainChatMessage.bot(response.reply));
        _isChatRunning = false;
        _chatStatus = null;
      });
      if (mounted) {
        await AppActionRuntime.execute(context, response.actions);
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _chatMessages.add(_MainChatMessage.bot('응답을 불러오지 못했습니다.'));
        _isChatRunning = false;
        _chatStatus = null;
      });
    }
  }

  Widget _buildShortcutButtons(SeasonColors colors) {
    final List<_HomeMenuItem> items = <_HomeMenuItem>[
      _HomeMenuItem(
        Icons.menu_book_outlined,
        '도감',
        () => _goTo(context, const FlowerBookPage()),
      ),
      _HomeMenuItem(
        Icons.directions_walk,
        '산책',
        () => _goTo(context, const PedometerScreen()),
      ),
      _HomeMenuItem(
        Icons.bookmark_outline,
        '저장',
        () => _goTo(context, const SavedPage()),
      ),
    ];

    return Row(
      children: <Widget>[
        for (final _HomeMenuItem item in items) ...<Widget>[
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
                    border: Border.all(
                      color: colors.primary.withValues(alpha: 0.12),
                    ),
                    boxShadow: <BoxShadow>[
                      BoxShadow(
                        color: colors.primary.withValues(alpha: 0.10),
                        blurRadius: 10,
                        offset: const Offset(0, 3),
                      ),
                    ],
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

  Widget _buildFestivalSection(SeasonColors colors) {
    if (_isLoadingFestivals) {
      return SizedBox(
        height: 120,
        child: Center(child: CircularProgressIndicator(color: colors.primary)),
      );
    }

    if (_festivalError != null) {
      return Container(
        padding: const EdgeInsets.all(16),
        decoration: _panelDecoration(colors),
        child: Text(
          '축제 데이터를 불러오지 못했습니다.',
          style: TextStyle(
            color: Colors.grey.shade700,
            fontSize: 13,
            fontWeight: FontWeight.w600,
          ),
        ),
      );
    }

    if (_festivals.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(16),
        decoration: _panelDecoration(colors),
        child: Text(
          '표시할 축제 정보가 없습니다.',
          style: TextStyle(
            color: Colors.grey.shade700,
            fontSize: 13,
            fontWeight: FontWeight.w600,
          ),
        ),
      );
    }

    return Column(
      children: <Widget>[
        SizedBox(
          height: 224,
          child: PageView.builder(
            controller: _festivalPageController,
            itemCount: _festivals.length,
            onPageChanged: (int index) {
              if (!mounted) return;
              setState(() => _currentFestivalPage = index);
            },
            itemBuilder: (BuildContext context, int index) {
              final FestivalData festival = _festivals[index];
              final bool isActive = index == _currentFestivalPage;
              return AnimatedPadding(
                duration: const Duration(milliseconds: 180),
                padding: EdgeInsets.only(
                  right: 10,
                  top: isActive ? 0 : 10,
                  bottom: isActive ? 0 : 10,
                ),
                child: _buildFestivalBanner(colors, festival),
              );
            },
          ),
        ),
        const SizedBox(height: 12),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: List<Widget>.generate(_festivals.length, (int index) {
            final bool isActive = index == _currentFestivalPage;
            return AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              width: isActive ? 18 : 7,
              height: 7,
              margin: const EdgeInsets.symmetric(horizontal: 3),
              decoration: BoxDecoration(
                color: isActive
                    ? colors.primary
                    : colors.primary.withValues(alpha: 0.24),
                borderRadius: BorderRadius.circular(999),
              ),
            );
          }),
        ),
      ],
    );
  }

  Widget _buildFestivalBanner(SeasonColors colors, FestivalData festival) {
    return InkWell(
      borderRadius: BorderRadius.circular(24),
      onTap: () => _openFestivalMap(festival),
      child: Container(
        decoration: _panelDecoration(colors),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          fit: StackFit.expand,
          children: <Widget>[
            festival.hasImage
                ? Image.network(
                    festival.imageUrl,
                    fit: BoxFit.cover,
                    cacheWidth: 300,
                    filterQuality: FilterQuality.medium,
                    errorBuilder: (_, __, ___) =>
                        _festivalBannerPlaceholder(colors),
                  )
                : _festivalBannerPlaceholder(colors),
            DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: <Color>[
                    Colors.black.withValues(alpha: 0.08),
                    Colors.black.withValues(alpha: 0.18),
                    Colors.black.withValues(alpha: 0.62),
                  ],
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 18, 18, 18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 6,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.18),
                      borderRadius: BorderRadius.circular(999),
                      border: Border.all(
                        color: Colors.white.withValues(alpha: 0.24),
                      ),
                    ),
                    child: const Text(
                      '계절 축제',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 11,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  const Spacer(),
                  if (festival.periodString.isNotEmpty)
                    Text(
                      festival.periodString,
                      style: TextStyle(
                        color: colors.primary.withValues(alpha: 0.98),
                        fontSize: 12,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  const SizedBox(height: 8),
                  Text(
                    festival.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 22,
                      fontWeight: FontWeight.w900,
                      height: 1.15,
                    ),
                  ),
                  if (festival.fullAddress.isNotEmpty) ...<Widget>[
                    const SizedBox(height: 8),
                    Text(
                      festival.fullAddress,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: Colors.white.withValues(alpha: 0.88),
                        fontSize: 13,
                        height: 1.35,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _festivalBannerPlaceholder(SeasonColors colors) {
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: <Color>[
            colors.primary.withValues(alpha: 0.45),
            Colors.orange.shade200,
            Colors.white,
          ],
        ),
      ),
      child: Center(
        child: Icon(
          Icons.local_florist_rounded,
          color: colors.primary,
          size: 54,
        ),
      ),
    );
  }

  Widget _sectionTitle(String text, SeasonColors colors) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Text(
        text,
        style: TextStyle(
          color: colors.primary,
          fontSize: 16,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }

  Widget _buildPostPreviewStrip(SeasonColors colors) {
    if (_isLoadingPosts) {
      return SizedBox(
        height: 96,
        child: Center(child: CircularProgressIndicator(color: colors.primary)),
      );
    }
    if (_posts.isEmpty) {
      return Container(
        height: 86,
        alignment: Alignment.center,
        decoration: _panelDecoration(colors),
        child: Text(
          '표시할 게시글이 없습니다.',
          style: TextStyle(color: Colors.grey.shade600, fontSize: 13),
        ),
      );
    }

    return SizedBox(
      height: 104,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: _posts.length,
        separatorBuilder: (_, __) => const SizedBox(width: 10),
        itemBuilder: (BuildContext context, int index) {
          final CommunityPost post = _posts[index];
          return GestureDetector(
            onTap: () => _goTo(context, CommunityFeedScreen(initialPostId: post.id)),
            child: Container(
            width: 132,
            decoration: _panelDecoration(colors),
            clipBehavior: Clip.antiAlias,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Expanded(
                  child: post.imageUrl == null || post.imageUrl!.isEmpty
                      ? Container(
                          width: double.infinity,
                          color: colors.primary.withValues(alpha: 0.12),
                          child: Icon(
                            Icons.article_outlined,
                            color: colors.primary,
                          ),
                        )
                      : Image.network(
                          post.imageUrl!,
                          width: double.infinity,
                          fit: BoxFit.cover,
                          errorBuilder: (_, __, ___) => Container(
                            width: double.infinity,
                            color: colors.primary.withValues(alpha: 0.12),
                            child: Icon(
                              Icons.article_outlined,
                              color: colors.primary,
                            ),
                          ),
                        ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(9, 6, 9, 7),
                  child: Text(
                    post.content,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
          )); // GestureDetector 닫기
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
        children: <Widget>[
          Icon(Icons.directions_walk, color: colors.primary, size: 28),
          const SizedBox(width: 12),
          const Expanded(
            child: Text(
              '산책 기록과 요약 정보가 이 영역에 표시될 예정입니다.',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
    );
  }

  BoxDecoration _panelDecoration(SeasonColors colors) {
    return BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(14),
      boxShadow: <BoxShadow>[
        BoxShadow(
          color: colors.primary.withValues(alpha: 0.10),
          blurRadius: 10,
          offset: const Offset(0, 3),
        ),
      ],
    );
  }

  void _goTo(BuildContext context, Widget screen) {
    Navigator.push(context, MaterialPageRoute(builder: (_) => screen));
  }

  void _openFestivalMap(FestivalData festival) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => KakaoMapScreen(initialFestival: festival),
      ),
    );
  }
}

class _HomeMenuItem {
  const _HomeMenuItem(this.icon, this.label, this.onTap);

  final IconData icon;
  final String label;
  final VoidCallback onTap;
}

class _MainChatMessage {
  const _MainChatMessage._({required this.text, required this.isUser});

  factory _MainChatMessage.user(String text) {
    return _MainChatMessage._(text: text, isUser: true);
  }

  factory _MainChatMessage.bot(String text) {
    return _MainChatMessage._(text: text, isUser: false);
  }

  final String text;
  final bool isUser;
}
