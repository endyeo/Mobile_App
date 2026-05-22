import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/auth_api_service.dart';
import '../services/community_api_service.dart';
import '../theme/season_theme.dart';
import 'community_feed_screen.dart';

class ProfileDetailScreen extends StatefulWidget {
  const ProfileDetailScreen({super.key});

  @override
  State<ProfileDetailScreen> createState() => _ProfileDetailScreenState();
}

class _ProfileDetailScreenState extends State<ProfileDetailScreen> {
  String _nickname = '사용자';
  String? _profileImageUrl;
  bool _isUploadingImage = false;
  List<CommunityPost> _likedPosts = <CommunityPost>[];
  bool _isLoadingLiked = true;

  @override
  void initState() {
    super.initState();
    _loadProfile();
    _loadLikedPosts();
  }

  Future<void> _loadProfile() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _nickname = prefs.getString('nickname') ?? '사용자';
      _profileImageUrl = prefs.getString('profileImageUrl');
    });
  }

  Future<void> _loadLikedPosts() async {
    setState(() => _isLoadingLiked = true);
    final result = await CommunityApiService.getLikedPosts();
    if (!mounted) return;
    setState(() {
      _likedPosts = result.posts;
      _isLoadingLiked = false;
    });
  }

  Future<void> _editNickname(SeasonColors colors) async {
    final controller = TextEditingController(text: _nickname);
    final newNickname = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('닉네임 변경'),
        content: TextField(
          controller: controller,
          maxLength: 10,
          autofocus: true,
          decoration: const InputDecoration(hintText: '2~10자', counterText: ''),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, controller.text.trim()),
            child: const Text('저장'),
          ),
        ],
      ),
    );

    if (newNickname == null || newNickname.isEmpty || newNickname == _nickname)
      return;
    if (newNickname.length < 2 || newNickname.length > 10) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('닉네임은 2~10자여야 합니다.')));
      }
      return;
    }

    final saved = await AuthApiService.updateNickname(newNickname);
    if (!mounted) return;
    if (saved != null) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('nickname', saved);
      if (!mounted) return;
      setState(() => _nickname = saved);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('닉네임이 변경됐습니다.')));
    } else {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('닉네임 변경 실패. 다시 시도해주세요.')));
    }
  }

  Future<void> _changeProfileImage(SeasonColors colors) async {
    if (_isUploadingImage) return;
    final picker = ImagePicker();
    final picked = await picker.pickImage(
      source: ImageSource.gallery,
      imageQuality: 85,
      maxWidth: 720,
      requestFullMetadata: false,
    );
    if (picked == null) return;

    setState(() => _isUploadingImage = true);
    final url = await AuthApiService.updateProfileImage(File(picked.path));
    if (!mounted) return;
    if (url != null) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('profileImageUrl', url);
      if (!mounted) return;
      setState(() {
        _profileImageUrl = url;
        _isUploadingImage = false;
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('프로필 사진이 변경됐습니다.')));
    } else {
      setState(() => _isUploadingImage = false);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('업로드 실패. 다시 시도해주세요.')));
    }
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
        title: Text(
          '내 정보',
          style: TextStyle(color: colors.primary, fontWeight: FontWeight.bold),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        children: [
          _buildProfileHeader(colors),
          const SizedBox(height: 20),
          _buildSectionTitle('내 정보', colors),
          const SizedBox(height: 8),
          _buildInfoCard(colors),
          const SizedBox(height: 24),
          _buildSectionTitle('좋아요 한 게시글', colors),
          const SizedBox(height: 8),
          _buildLikedPostsSection(colors),
        ],
      ),
    );
  }

  Widget _buildSectionTitle(String label, SeasonColors colors) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w800,
          color: colors.primary,
        ),
      ),
    );
  }

  Widget _buildProfileHeader(SeasonColors colors) {
    return Center(
      child: Column(
        children: [
          GestureDetector(
            onTap: () => _changeProfileImage(colors),
            child: Stack(
              children: [
                Container(
                  width: 96,
                  height: 96,
                  decoration: BoxDecoration(
                    color: colors.primary.withValues(alpha: 0.14),
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: colors.primary.withValues(alpha: 0.4),
                      width: 2,
                    ),
                    image: _profileImageUrl != null
                        ? DecorationImage(
                            image: NetworkImage(_profileImageUrl!),
                            fit: BoxFit.cover,
                          )
                        : null,
                  ),
                  child: _profileImageUrl == null
                      ? Icon(
                          Icons.person_outline,
                          color: colors.primary,
                          size: 48,
                        )
                      : null,
                ),
                if (_isUploadingImage)
                  Positioned.fill(
                    child: Container(
                      decoration: const BoxDecoration(
                        color: Colors.black54,
                        shape: BoxShape.circle,
                      ),
                      child: const Center(
                        child: SizedBox(
                          width: 24,
                          height: 24,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        ),
                      ),
                    ),
                  ),
                Positioned(
                  right: 0,
                  bottom: 0,
                  child: Container(
                    width: 30,
                    height: 30,
                    decoration: BoxDecoration(
                      color: colors.primary,
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white, width: 2),
                    ),
                    child: const Icon(
                      Icons.camera_alt,
                      size: 16,
                      color: Colors.white,
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Text(
            _nickname,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoCard(SeasonColors colors) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: colors.primary.withValues(alpha: 0.08),
            blurRadius: 10,
            offset: const Offset(0, 3),
          ),
        ],
      ),
      child: Column(
        children: [
          _buildInfoRow(
            icon: Icons.person_outline,
            label: '닉네임',
            value: _nickname,
            actionIcon: Icons.edit_outlined,
            onAction: () => _editNickname(colors),
            colors: colors,
          ),
          const Divider(height: 1),
          _buildInfoRow(
            icon: Icons.image_outlined,
            label: '프로필 사진',
            value: _profileImageUrl != null ? '설정됨' : '미설정',
            actionIcon: Icons.edit_outlined,
            onAction: () => _changeProfileImage(colors),
            colors: colors,
          ),
        ],
      ),
    );
  }

  Widget _buildInfoRow({
    required IconData icon,
    required String label,
    required String value,
    required IconData actionIcon,
    required VoidCallback onAction,
    required SeasonColors colors,
  }) {
    return InkWell(
      onTap: onAction,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            Icon(icon, size: 22, color: colors.primary),
            const SizedBox(width: 12),
            Text(
              label,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
            ),
            const Spacer(),
            Text(
              value,
              style: TextStyle(fontSize: 13, color: Colors.grey[600]),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            const SizedBox(width: 8),
            Icon(actionIcon, size: 18, color: Colors.grey[500]),
          ],
        ),
      ),
    );
  }

  Widget _buildLikedPostsSection(SeasonColors colors) {
    if (_isLoadingLiked) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 24),
        child: Center(child: CircularProgressIndicator(color: colors.primary)),
      );
    }
    if (_likedPosts.isEmpty) {
      return Container(
        padding: const EdgeInsets.symmetric(vertical: 32),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          children: [
            Icon(Icons.favorite_border, size: 36, color: Colors.grey[400]),
            const SizedBox(height: 8),
            Text(
              '아직 좋아요 한 게시글이 없어요',
              style: TextStyle(color: Colors.grey[500], fontSize: 13),
            ),
          ],
        ),
      );
    }
    return Column(
      children: _likedPosts
          .map((post) => _buildLikedPostTile(post, colors))
          .toList(),
    );
  }

  Widget _buildLikedPostTile(CommunityPost post, SeasonColors colors) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => CommunityFeedScreen(initialPostId: post.id),
            ),
          );
        },
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: SizedBox(
                  width: 64,
                  height: 64,
                  child: post.imageUrl != null && post.imageUrl!.isNotEmpty
                      ? Image.network(
                          post.imageUrl!,
                          fit: BoxFit.cover,
                          cacheWidth: 200,
                          errorBuilder: (_, __, ___) => Container(
                            color: colors.primary.withValues(alpha: 0.12),
                            child: Icon(
                              Icons.broken_image,
                              color: colors.primary.withValues(alpha: 0.5),
                            ),
                          ),
                        )
                      : Container(
                          color: colors.primary.withValues(alpha: 0.12),
                          child: Icon(
                            Icons.local_florist,
                            color: colors.primary.withValues(alpha: 0.6),
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
                    if (post.displaySpecies != null)
                      Text(
                        post.displaySpecies!,
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w800,
                          color: colors.primary,
                        ),
                      ),
                    const SizedBox(height: 2),
                    Text(
                      post.content,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 13, height: 1.3),
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
                        Text(
                          post.user,
                          style: TextStyle(
                            fontSize: 11,
                            color: Colors.grey[500],
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
