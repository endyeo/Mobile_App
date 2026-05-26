import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/community_api_service.dart';
import '../widgets/chat_floating_button.dart';

class CreatePostScreen extends StatefulWidget {
  const CreatePostScreen({super.key});

  @override
  State<CreatePostScreen> createState() => _CreatePostScreenState();
}

class _CreatePostScreenState extends State<CreatePostScreen> {
  final _contentController = TextEditingController();
  File? _selectedImage;
  String? _selectedSpecies;
  bool _isLoading = false;

  static const _species = ['벚꽃', '개나리', '진달래', '튤립', '장미', '수국', '코스모스', '기타'];

  @override
  void dispose() {
    _contentController.dispose();
    super.dispose();
  }

  Future<void> _pickImage(ImageSource source) async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(
      source: source,
      imageQuality: 70,
      maxWidth: 1080,
      requestFullMetadata: false,
    );
    if (picked != null) setState(() => _selectedImage = File(picked.path));
  }

  void _showImagePicker() {
    final colors = SeasonTheme.getColors();
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: Icon(Icons.camera_alt, color: colors.primary),
              title: const Text('카메라'),
              onTap: () {
                Navigator.pop(context);
                _pickImage(ImageSource.camera);
              },
            ),
            ListTile(
              leading: Icon(Icons.photo_library, color: colors.primary),
              title: const Text('갤러리'),
              onTap: () {
                Navigator.pop(context);
                _pickImage(ImageSource.gallery);
              },
            ),
            if (_selectedImage != null)
              ListTile(
                leading: const Icon(Icons.delete, color: Colors.red),
                title: const Text('이미지 제거'),
                onTap: () {
                  Navigator.pop(context);
                  setState(() => _selectedImage = null);
                },
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _submit() async {
    if (_contentController.text.trim().isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('내용을 입력해 주세요.')));
      return;
    }

    setState(() => _isLoading = true);

    try {
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString('accessToken') ?? '';

      final post = await CommunityApiService.createPost(
        accessToken: token,
        content: _contentController.text.trim(),
        flowerSpecies: _selectedSpecies,
        image: _selectedImage,
      );

      if (!mounted) return;

      if (post != null) {
        Navigator.pop(context, post);
      } else {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('게시글 등록에 실패했습니다.')));
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
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
          icon: Icon(Icons.close, color: colors.primary),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text(
          '새 게시글',
          style: TextStyle(color: colors.primary, fontWeight: FontWeight.bold),
        ),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: TextButton(
              onPressed: _isLoading ? null : _submit,
              child: _isLoading
                  ? SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: colors.primary,
                      ),
                    )
                  : Text(
                      '등록',
                      style: TextStyle(
                        color: colors.primary,
                        fontWeight: FontWeight.bold,
                        fontSize: 15,
                      ),
                    ),
            ),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 이미지 선택
            GestureDetector(
              onTap: _showImagePicker,
              child: Container(
                height: 200,
                width: double.infinity,
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: colors.primary.withOpacity(0.3)),
                ),
                child: _selectedImage != null
                    ? ClipRRect(
                        borderRadius: BorderRadius.circular(16),
                        child: Image.file(
                          _selectedImage!,
                          fit: BoxFit.cover,
                          cacheWidth: 1080,
                          filterQuality: FilterQuality.medium,
                        ),
                      )
                    : Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(
                            Icons.add_photo_alternate_outlined,
                            size: 48,
                            color: colors.primary.withOpacity(0.5),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            '사진 추가',
                            style: TextStyle(
                              color: colors.primary.withOpacity(0.6),
                            ),
                          ),
                        ],
                      ),
              ),
            ),
            const SizedBox(height: 16),

            // 꽃 종류 선택
            Text(
              '꽃 종류',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color: colors.primary,
              ),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: _species.map((s) {
                final isSelected = s == _selectedSpecies;
                return ChoiceChip(
                  label: Text(s),
                  selected: isSelected,
                  selectedColor: colors.primary,
                  labelStyle: TextStyle(
                    color: isSelected ? Colors.white : Colors.grey[700],
                  ),
                  onSelected: (_) =>
                      setState(() => _selectedSpecies = isSelected ? null : s),
                );
              }).toList(),
            ),
            const SizedBox(height: 16),

            // 내용 입력
            Text(
              '내용',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color: colors.primary,
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _contentController,
              maxLines: 5,
              maxLength: 500,
              decoration: InputDecoration(
                hintText: '오늘 발견한 꽃에 대해 이야기해 주세요...',
                filled: true,
                fillColor: Colors.white,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(14),
                  borderSide: BorderSide(color: colors.accent),
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(14),
                  borderSide: BorderSide(color: colors.accent.withOpacity(0.5)),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(14),
                  borderSide: BorderSide(color: colors.primary, width: 2),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
