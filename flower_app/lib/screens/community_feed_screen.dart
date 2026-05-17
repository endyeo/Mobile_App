import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/community_api_service.dart';
import '../widgets/app_bottom_navigation.dart';
import '../widgets/chat_floating_button.dart';
import 'create_flower_spot_screen.dart';
import '../widgets/comment_bottom_sheet.dart';

class CommunityFeedScreen extends StatefulWidget {
  final int? initialPostId; // 메인화면 미리보기 탭 시 해당 게시글로 스크롤
  final String? initialQuery;

  const CommunityFeedScreen({super.key, this.initialPostId, this.initialQuery});

  @override
  State<CommunityFeedScreen> createState() => _CommunityFeedScreenState();
}

class _CommunityFeedScreenState extends State<CommunityFeedScreen> {
  List<CommunityPost> _posts = [];
  bool _isLoading = true;
  String? _error;
  String _accessToken = '';
  String _activeQuery = '';
  final Map<int, GlobalKey> _postKeys = {};

  @override
  void initState() {
    super.initState();
    _activeQuery = widget.initialQuery?.trim() ?? '';
    _loadPosts();
  }

  Future<void> _loadPosts() async {
    if (mounted)
      setState(() {
        _isLoading = true;
        _error = null;
      });
    try {
      final prefs = await SharedPreferences.getInstance();
      _accessToken = prefs.getString('accessToken') ?? '';
      final posts = await CommunityApiService.getPosts(_accessToken);
      if (mounted) {
        setState(() {
          _posts = _filterPosts(posts);
          _isLoading = false;
        });
        _scrollToInitialPost();
      }
    } catch (e) {
      if (mounted)
        setState(() {
          _isLoading = false;
          _error = '게시글을 불러오지 못했습니다.';
        });
    }
  }

  void _scrollToInitialPost() {
    if (widget.initialPostId == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final key = _postKeys[widget.initialPostId!];
      if (key?.currentContext != null) {
        Scrollable.ensureVisible(
          key!.currentContext!,
          duration: const Duration(milliseconds: 400),
          curve: Curves.easeInOut,
          alignment: 0.1,
        );
      }
    });
  }

  List<CommunityPost> _filterPosts(List<CommunityPost> posts) {
    if (_activeQuery.isEmpty) return posts;
    final query = _activeQuery.toLowerCase();
    return posts.where((post) {
      return post.content.toLowerCase().contains(query) ||
          (post.flowerSpecies?.toLowerCase().contains(query) ?? false) ||
          (post.address?.toLowerCase().contains(query) ?? false) ||
          post.user.toLowerCase().contains(query);
    }).toList();
  }

  Future<void> _openCreatePost() async {
    final result = await Navigator.push<bool>(
      context,
      MaterialPageRoute(builder: (_) => const CreateFlowerSpotScreen()),
    );
    if (result == true) _loadPosts(); // 게시 후 피드 새로고침
  }

  Future<void> _toggleLike(int index) async {
    final post = _posts[index];
    final originalLiked = post.liked;
    final originalCount = post.likeCount;
    setState(() {
      post.liked = !post.liked;
      post.likeCount += post.liked ? 1 : -1;
    });
    try {
      await CommunityApiService.toggleLike(_accessToken, post.id);
    } catch (_) {
      if (mounted)
        setState(() {
          post.liked = originalLiked;
          post.likeCount = originalCount;
        });
    }
  }

  Future<void> _toggleSave(int index) async {
    final post = _posts[index];
    final original = post.saved;
    setState(() => post.saved = !post.saved);
    try {
      await CommunityApiService.toggleSave(_accessToken, post.id);
    } catch (_) {
      if (mounted) setState(() => post.saved = original);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    return Scaffold(
      backgroundColor: colors.background,
      floatingActionButton: const ChatFloatingButton(),
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
      bottomNavigationBar: const AppBottomNavigation(
        currentTab: AppNavTab.community,
      ),
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new, color: colors.primary, size: 18),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text(
          '커뮤니티',
          style: TextStyle(color: colors.primary, fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            icon: Icon(Icons.add_circle_outline, color: colors.primary),
            onPressed: _openCreatePost,
          ),
        ],
      ),
      body: _isLoading
          ? Center(child: CircularProgressIndicator(color: colors.primary))
          : _error != null
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.cloud_off, size: 48, color: Colors.grey[400]),
                  const SizedBox(height: 12),
                  Text(_error!, style: TextStyle(color: Colors.grey[500])),
                  const SizedBox(height: 8),
                  ElevatedButton(
                    onPressed: _loadPosts,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: colors.primary,
                    ),
                    child: const Text(
                      '다시 시도',
                      style: TextStyle(color: Colors.white),
                    ),
                  ),
                ],
              ),
            )
          : RefreshIndicator(
              onRefresh: _loadPosts,
              child: _posts.isEmpty
                  ? _buildEmpty(colors)
                  : ListView.builder(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 8,
                      ),
                      itemCount: _posts.length + (_activeQuery.isEmpty ? 0 : 1),
                      itemBuilder: (context, index) {
                        if (_activeQuery.isNotEmpty && index == 0) {
                          return _buildQueryHeader(colors);
                        }
                        final postIndex = _activeQuery.isEmpty ? index : index - 1;
                        final post = _posts[postIndex];
                        _postKeys[post.id] ??= GlobalKey();
                        return KeyedSubtree(
                          key: _postKeys[post.id],
                          child: _buildPostCard(post, colors, postIndex),
                        );
                      },
                    ),
            ),
    );
  }

  Widget _buildEmpty(SeasonColors colors) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.local_florist_outlined, size: 60, color: Colors.grey[300]),
          const SizedBox(height: 12),
          Text(
            _activeQuery.isEmpty ? '아직 게시글이 없어요' : '"$_activeQuery" 관련 게시글이 없어요',
            style: TextStyle(color: Colors.grey[500], fontSize: 16),
          ),
          const SizedBox(height: 8),
          ElevatedButton(
            onPressed: _openCreatePost,
            style: ElevatedButton.styleFrom(backgroundColor: colors.primary),
            child: const Text(
              '첫 게시글 작성하기',
              style: TextStyle(color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildQueryHeader(SeasonColors colors) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: colors.primary.withAlpha(35)),
      ),
      child: Row(
        children: [
          Icon(Icons.search, color: colors.primary, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '"$_activeQuery" 관련 후기',
              style: TextStyle(color: colors.primary, fontWeight: FontWeight.w700),
            ),
          ),
          TextButton(
            onPressed: () {
              setState(() => _activeQuery = '');
              _loadPosts();
            },
            child: const Text('전체'),
          ),
        ],
      ),
    );
  }

  Widget _buildPostCard(CommunityPost post, SeasonColors colors, int index) {
    final flowerColors = [
      const Color(0xFFFFB7C5),
      const Color(0xFFFFE082),
      const Color(0xFFE8A0BF),
      const Color(0xFFF5F5F5),
      const Color(0xFFFF6B6B),
    ];

    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: colors.primary.withAlpha(15),
            blurRadius: 10,
            offset: const Offset(0, 3),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                CircleAvatar(
                  radius: 20,
                  backgroundColor: colors.primary.withAlpha(25),
                  backgroundImage: post.profileImageUrl != null
                      ? NetworkImage(post.profileImageUrl!)
                      : null,
                  child: post.profileImageUrl == null
                      ? Text(
                          post.user.isNotEmpty ? post.user[0] : '?',
                          style: TextStyle(
                            color: colors.primary,
                            fontWeight: FontWeight.bold,
                          ),
                        )
                      : null,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        post.user,
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 14,
                        ),
                      ),
                      Row(
                        children: [
                          if (post.address != null) ...[
                            Icon(
                              Icons.location_on,
                              size: 12,
                              color: Colors.grey[400],
                            ),
                            const SizedBox(width: 2),
                            Text(
                              post.address!,
                              style: TextStyle(
                                fontSize: 11,
                                color: Colors.grey[500],
                              ),
                            ),
                            const SizedBox(width: 8),
                          ],
                          Text(
                            post.time,
                            style: TextStyle(
                              fontSize: 11,
                              color: Colors.grey[400],
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                if (post.flowerSpecies != null)
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: colors.primary.withAlpha(20),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      post.flowerSpecies!,
                      style: TextStyle(
                        fontSize: 11,
                        color: colors.primary,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
              ],
            ),
          ),
          if (post.imageUrl != null)
            Image.network(
              post.imageUrl!,
              height: 200,
              width: double.infinity,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => Container(
                height: 200,
                color: flowerColors[index % flowerColors.length].withAlpha(80),
                child: const Icon(
                  Icons.broken_image,
                  size: 48,
                  color: Colors.grey,
                ),
              ),
            )
          else
            Container(
              height: 120,
              color: flowerColors[index % flowerColors.length].withAlpha(80),
              child: Center(
                child: Icon(
                  Icons.local_florist,
                  size: 48,
                  color: colors.primary.withAlpha(100),
                ),
              ),
            ),
          Padding(
            padding: const EdgeInsets.all(14),
            child: Text(
              post.content,
              style: const TextStyle(fontSize: 14, height: 1.5),
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(left: 8, right: 14, bottom: 10),
            child: Row(
              children: [
                IconButton(
                  icon: Icon(
                    post.liked ? Icons.favorite : Icons.favorite_border,
                    color: post.liked ? Colors.red[400] : Colors.grey[400],
                    size: 22,
                  ),
                  onPressed: () => _toggleLike(index),
                ),
                Text(
                  '${post.likeCount}',
                  style: TextStyle(fontSize: 13, color: Colors.grey[600]),
                ),
                const SizedBox(width: 16),
                GestureDetector(
                  onTap: () => showCommentSheet(
                    context,
                    post.id,
                    onCommentAdded: () => setState(() => post.commentCount++),
                  ),
                  child: Row(
                    children: [
                      Icon(
                        Icons.chat_bubble_outline,
                        size: 20,
                        color: Colors.grey[400],
                      ),
                      const SizedBox(width: 4),
                      Text(
                        formatCommentCount(post.commentCount),
                        style: TextStyle(fontSize: 13, color: Colors.grey[600]),
                      ),
                    ],
                  ),
                ),
                const Spacer(),
                IconButton(
                  icon: Icon(
                    post.saved ? Icons.bookmark : Icons.bookmark_border,
                    color: post.saved ? colors.primary : Colors.grey[400],
                    size: 22,
                  ),
                  onPressed: () => _toggleSave(index),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
