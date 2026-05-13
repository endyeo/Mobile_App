import '../widgets/chat_floating_button.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/saved_api_service.dart';

class SavedPage extends StatefulWidget {
  const SavedPage({super.key});

  @override
  State<SavedPage> createState() => _SavedPageState();
}

class _SavedPageState extends State<SavedPage> with SingleTickerProviderStateMixin {
  late TabController _tabController;
  List<SavedPostItem> _savedPosts = [];
  List<SavedSpotItem> _savedSpots = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    _loadData();
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString('accessToken') ?? '';
    final posts = await SavedApiService.getSavedPosts(token);
    final spots = await SavedApiService.getSavedSpots(token);
    if (mounted) setState(() { _savedPosts = posts; _savedSpots = spots; _isLoading = false; });
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
        title: Text('저장됨', style: TextStyle(color: colors.primary, fontWeight: FontWeight.bold)),
        bottom: TabBar(
          controller: _tabController,
          labelColor: colors.primary,
          unselectedLabelColor: Colors.grey[400],
          indicatorColor: colors.primary,
          indicatorWeight: 3,
          tabs: const [
            Tab(icon: Icon(Icons.article_outlined), text: '게시글'),
            Tab(icon: Icon(Icons.place_outlined), text: '꽃 스팟'),
          ],
        ),
      ),
      body: _isLoading
          ? Center(child: CircularProgressIndicator(color: colors.primary))
          : TabBarView(
              controller: _tabController,
              children: [_buildSavedPosts(colors), _buildSavedSpots(colors)],
            ),
    );
  }

  Widget _buildSavedPosts(SeasonColors colors) {
    if (_savedPosts.isEmpty) return _emptyState('저장한 게시글이 없어요', '커뮤니티에서 마음에 드는 게시글을 저장해보세요!');
    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: _savedPosts.length,
      itemBuilder: (context, i) {
        final post = _savedPosts[i];
        return Dismissible(
          key: ValueKey(post.id),
          direction: DismissDirection.endToStart,
          background: Container(
            alignment: Alignment.centerRight,
            padding: const EdgeInsets.only(right: 20),
            margin: const EdgeInsets.only(bottom: 12),
            decoration: BoxDecoration(color: Colors.red[100], borderRadius: BorderRadius.circular(16)),
            child: Icon(Icons.delete_outline, color: Colors.red[400]),
          ),
          onDismissed: (_) async {
            final removed = _savedPosts[i];
            setState(() => _savedPosts.removeAt(i));
            try {
              final prefs = await SharedPreferences.getInstance();
              final token = prefs.getString('accessToken') ?? '';
              await SavedApiService.unsavePost(token, post.id);
              if (mounted) ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('저장 목록에서 삭제되었습니다'), duration: Duration(seconds: 1)),
              );
            } catch (_) {
              if (mounted) setState(() => _savedPosts.insert(i, removed));
            }
          },
          child: Container(
            margin: const EdgeInsets.only(bottom: 12),
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.white, borderRadius: BorderRadius.circular(16),
              boxShadow: [BoxShadow(color: colors.primary.withAlpha(15), blurRadius: 8)],
            ),
            child: Row(
              children: [
                CircleAvatar(
                  radius: 22, backgroundColor: colors.primary.withAlpha(20),
                  child: Text(post.avatar, style: const TextStyle(fontSize: 24)),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(children: [
                        Text(post.user, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
                        const Spacer(),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(color: colors.primary.withAlpha(20), borderRadius: BorderRadius.circular(8)),
                          child: Text(post.flowerSpecies, style: TextStyle(fontSize: 10, color: colors.primary)),
                        ),
                      ]),
                      const SizedBox(height: 4),
                      Text(post.content, maxLines: 2, overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 13, color: Colors.grey[700])),
                      const SizedBox(height: 4),
                      Row(children: [
                        Icon(Icons.favorite, size: 12, color: Colors.red[300]),
                        const SizedBox(width: 3),
                        Text('${post.likeCount}', style: TextStyle(fontSize: 11, color: Colors.grey[500])),
                        const SizedBox(width: 12),
                        Text(post.time, style: TextStyle(fontSize: 11, color: Colors.grey[400])),
                      ]),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Icon(Icons.bookmark, color: colors.primary, size: 20),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildSavedSpots(SeasonColors colors) {
    if (_savedSpots.isEmpty) return _emptyState('저장한 꽃 스팟이 없어요', '지도에서 마음에 드는 장소를 저장해보세요!');
    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: _savedSpots.length,
      itemBuilder: (context, i) {
        final spot = _savedSpots[i];
        return Dismissible(
          key: ValueKey('spot_${spot.id}'),
          direction: DismissDirection.endToStart,
          background: Container(
            alignment: Alignment.centerRight,
            padding: const EdgeInsets.only(right: 20),
            margin: const EdgeInsets.only(bottom: 12),
            decoration: BoxDecoration(color: Colors.red[100], borderRadius: BorderRadius.circular(16)),
            child: Icon(Icons.delete_outline, color: Colors.red[400]),
          ),
          onDismissed: (_) async {
            final prefs = await SharedPreferences.getInstance();
            final token = prefs.getString('accessToken') ?? '';
            await SavedApiService.unsaveSpot(token, spot.id);
            setState(() => _savedSpots.removeAt(i));
            if (mounted) ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('저장 목록에서 삭제되었습니다'), duration: Duration(seconds: 1)),
            );
          },
          child: Container(
            margin: const EdgeInsets.only(bottom: 12),
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.white, borderRadius: BorderRadius.circular(16),
              boxShadow: [BoxShadow(color: colors.primary.withAlpha(15), blurRadius: 8)],
            ),
            child: Row(
              children: [
                Container(
                  width: 52, height: 52,
                  decoration: BoxDecoration(color: colors.primary.withAlpha(20), borderRadius: BorderRadius.circular(14)),
                  child: Center(child: Text(spot.emoji, style: const TextStyle(fontSize: 28))),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(spot.name, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                      const SizedBox(height: 2),
                      Row(children: [
                        Icon(Icons.place, size: 12, color: Colors.grey[400]),
                        const SizedBox(width: 2),
                        Expanded(child: Text(spot.location, style: TextStyle(fontSize: 12, color: Colors.grey[500]), overflow: TextOverflow.ellipsis)),
                      ]),
                      const SizedBox(height: 2),
                      Text('🌼 ${spot.bloomPeriod}', style: TextStyle(fontSize: 11, color: colors.primary)),
                    ],
                  ),
                ),
                Icon(Icons.bookmark, color: colors.primary, size: 20),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _emptyState(String title, String subtitle) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.bookmark_border, size: 60, color: Colors.grey[300]),
          const SizedBox(height: 12),
          Text(title, style: TextStyle(fontSize: 16, color: Colors.grey[500])),
          const SizedBox(height: 4),
          Text(subtitle, style: TextStyle(fontSize: 13, color: Colors.grey[400])),
        ],
      ),
    );
  }
}
