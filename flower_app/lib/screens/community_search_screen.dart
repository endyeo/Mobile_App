import 'package:flutter/material.dart';

import '../services/community_api_service.dart';
import '../theme/season_theme.dart';
import 'community_feed_screen.dart';

enum _SortOption { latest, popular }

class CommunitySearchScreen extends StatefulWidget {
  const CommunitySearchScreen({super.key});

  @override
  State<CommunitySearchScreen> createState() => _CommunitySearchScreenState();
}

class _CommunitySearchScreenState extends State<CommunitySearchScreen> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  _SortOption _sort = _SortOption.latest;
  List<CommunityPost> _results = <CommunityPost>[];
  bool _isLoading = false;
  bool _hasSearched = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback(
      (_) => _focusNode.requestFocus(),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final String keyword = _controller.text.trim();
    if (keyword.isEmpty) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _isLoading = true;
      _hasSearched = true;
    });
    final FeedResult result = await CommunityApiService.searchPosts(
      keyword: keyword,
      sort: _sort == _SortOption.latest ? 'latest' : 'popular',
      limit: 30,
    );
    if (!mounted) return;
    setState(() {
      _results = result.posts;
      _isLoading = false;
    });
  }

  void _changeSort(_SortOption next) {
    if (_sort == next) return;
    setState(() => _sort = next);
    if (_hasSearched) _search();
  }

  void _openPost(CommunityPost post) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => CommunityFeedScreen(initialPostId: post.id),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    return Scaffold(
      backgroundColor: colors.background,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new, color: colors.primary, size: 18),
          onPressed: () => Navigator.pop(context),
        ),
        title: TextField(
          controller: _controller,
          focusNode: _focusNode,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => _search(),
          decoration: InputDecoration(
            hintText: '꽃 이름, 내용, 작성자 닉네임',
            hintStyle: TextStyle(color: Colors.grey[400], fontSize: 14),
            border: InputBorder.none,
            isDense: true,
            suffixIcon: _controller.text.isEmpty
                ? null
                : IconButton(
                    icon: const Icon(Icons.close, size: 18),
                    color: Colors.grey,
                    onPressed: () {
                      _controller.clear();
                      setState(() {});
                    },
                  ),
          ),
          onChanged: (_) => setState(() {}),
        ),
        actions: [
          IconButton(
            icon: Icon(Icons.search, color: colors.primary),
            onPressed: _search,
          ),
        ],
      ),
      body: Column(
        children: [
          _buildSortRow(colors),
          Expanded(child: _buildBody(colors)),
        ],
      ),
    );
  }

  Widget _buildSortRow(SeasonColors colors) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      color: Colors.white,
      child: Row(
        children: <Widget>[
          _sortChip(
            label: '최신순',
            selected: _sort == _SortOption.latest,
            onTap: () => _changeSort(_SortOption.latest),
            colors: colors,
          ),
          const SizedBox(width: 8),
          _sortChip(
            label: '인기순',
            selected: _sort == _SortOption.popular,
            onTap: () => _changeSort(_SortOption.popular),
            colors: colors,
          ),
        ],
      ),
    );
  }

  Widget _sortChip({
    required String label,
    required bool selected,
    required VoidCallback onTap,
    required SeasonColors colors,
  }) {
    return InkWell(
      borderRadius: BorderRadius.circular(20),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        decoration: BoxDecoration(
          color: selected ? colors.primary : colors.primary.withAlpha(20),
          borderRadius: BorderRadius.circular(20),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? Colors.white : colors.primary,
            fontSize: 12,
            fontWeight: FontWeight.w800,
          ),
        ),
      ),
    );
  }

  Widget _buildBody(SeasonColors colors) {
    if (_isLoading) {
      return Center(child: CircularProgressIndicator(color: colors.primary));
    }
    if (!_hasSearched) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.search, size: 56, color: Colors.grey[300]),
              const SizedBox(height: 12),
              Text(
                '찾을 키워드를 입력해주세요',
                style: TextStyle(color: Colors.grey[500], fontSize: 14),
              ),
            ],
          ),
        ),
      );
    }
    if (_results.isEmpty) {
      return Center(
        child: Text('검색 결과가 없습니다', style: TextStyle(color: Colors.grey[500])),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      itemCount: _results.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, i) => _buildResultCard(_results[i], colors),
    );
  }

  Widget _buildResultCard(CommunityPost post, SeasonColors colors) {
    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () => _openPost(post),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: SizedBox(
                  width: 68,
                  height: 68,
                  child: post.imageUrl != null && post.imageUrl!.isNotEmpty
                      ? Image.network(
                          post.imageUrl!,
                          fit: BoxFit.cover,
                          cacheWidth: 200,
                          errorBuilder: (_, __, ___) => Container(
                            color: colors.primary.withAlpha(12),
                            child: Icon(
                              Icons.broken_image,
                              color: colors.primary.withAlpha(120),
                            ),
                          ),
                        )
                      : Container(
                          color: colors.primary.withAlpha(12),
                          child: Icon(
                            Icons.local_florist,
                            color: colors.primary.withAlpha(120),
                          ),
                        ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Row(
                      children: [
                        if (post.displaySpecies != null)
                          Container(
                            margin: const EdgeInsets.only(right: 6),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 7,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: colors.primary.withAlpha(20),
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Text(
                              post.displaySpecies!,
                              style: TextStyle(
                                fontSize: 10,
                                color: colors.primary,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ),
                        Expanded(
                          child: Text(
                            post.user,
                            style: const TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w700,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      post.content,
                      style: const TextStyle(fontSize: 13, height: 1.3),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        Icon(Icons.favorite, size: 12, color: Colors.red[300]),
                        const SizedBox(width: 3),
                        Text(
                          '${post.likeCount}',
                          style: TextStyle(
                            fontSize: 11,
                            color: Colors.grey[600],
                          ),
                        ),
                        const SizedBox(width: 8),
                        Icon(
                          Icons.chat_bubble_outline,
                          size: 12,
                          color: Colors.grey[500],
                        ),
                        const SizedBox(width: 3),
                        Text(
                          '${post.commentCount}',
                          style: TextStyle(
                            fontSize: 11,
                            color: Colors.grey[600],
                          ),
                        ),
                      ],
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
}
