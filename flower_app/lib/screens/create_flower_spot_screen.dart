import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:geolocator/geolocator.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/flower_spot_api_service.dart';

class CreateFlowerSpotScreen extends StatefulWidget {
  const CreateFlowerSpotScreen({super.key});

  @override
  State<CreateFlowerSpotScreen> createState() => _CreateFlowerSpotScreenState();
}

class _CreateFlowerSpotScreenState extends State<CreateFlowerSpotScreen> {
  File? _image;
  bool _shareLocation = false;
  bool _notifyOthers = false;
  bool _isIdentifying = false;
  bool _isPosting = false;
  String? _plantName;
  double? _plantConfidence;
  Position? _currentPosition;
  final TextEditingController _contentController = TextEditingController();

  @override
  void dispose() {
    _contentController.dispose();
    super.dispose();
  }

  Future<void> _takePicture() async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(
      source: ImageSource.camera,
      imageQuality: 80,
      maxWidth: 1080,
    );
    if (picked == null) return;

    final file = File(picked.path);
    setState(() {
      _image = file;
      _plantName = null;
      _plantConfidence = null;
    });
    await _identifyPlant(file);
  }

  Future<void> _pickFromGallery() async {
    if (_shareLocation) return; // 위치 공유 시 카메라만
    final picker = ImagePicker();
    final picked = await picker.pickImage(
      source: ImageSource.gallery,
      imageQuality: 80,
      maxWidth: 1080,
    );
    if (picked == null) return;

    final file = File(picked.path);
    setState(() {
      _image = file;
      _plantName = null;
      _plantConfidence = null;
    });
    await _identifyPlant(file);
  }

  Future<void> _identifyPlant(File file) async {
    setState(() => _isIdentifying = true);
    try {
      final result = await FlowerSpotApiService.identifyPlant(file);
      if (mounted) {
        setState(() {
          _plantName = result['plantName'] as String? ?? '기타';
          _plantConfidence = (result['confidence'] as num?)?.toDouble();
          _isIdentifying = false;
        });
      }
    } catch (e) {
      debugPrint('[PlantId] 인식 실패: $e');
      if (mounted) setState(() { _plantName = '기타'; _isIdentifying = false; });
    }
  }

  Future<void> _toggleLocationShare(bool value) async {
    if (value) {
      try {
        LocationPermission permission = await Geolocator.checkPermission();
        if (permission == LocationPermission.denied) {
          permission = await Geolocator.requestPermission();
        }
        if (permission == LocationPermission.deniedForever) {
          if (mounted) _showLocationDeniedDialog();
          return;
        }
        final pos = await Geolocator.getCurrentPosition(
          locationSettings: const LocationSettings(accuracy: LocationAccuracy.high),
        );
        setState(() { _shareLocation = true; _currentPosition = pos; });
      } catch (e) {
        if (mounted) _showLocationDeniedDialog();
      }
    } else {
      setState(() {
        _shareLocation = false;
        _currentPosition = null;
        _notifyOthers = false;
      });
    }
  }

  void _showLocationDeniedDialog() {
    showDialog(context: context, builder: (ctx) => AlertDialog(
      title: const Text('위치 권한 필요'),
      content: const Text('위치 공유를 위해 위치 권한이 필요합니다.\n설정에서 허용해주세요.'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('확인')),
      ],
    ));
  }

  Future<void> _submit() async {
    if (_image == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('사진을 먼저 촬영해주세요.')),
      );
      return;
    }
    if (_plantName == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('식물 인식 중입니다. 잠시 기다려주세요.')),
      );
      return;
    }

    setState(() => _isPosting = true);
    try {
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString('accessToken') ?? '';

      await FlowerSpotApiService.createFlowerSpot(
        accessToken: token,
        image: _image!,
        content: _contentController.text.trim().isEmpty ? null : _contentController.text.trim(),
        plantName: _plantName!,
        plantConfidence: _plantConfidence?.toDouble() ?? 0.0,
        latitude: _shareLocation ? _currentPosition?.latitude : null,
        longitude: _shareLocation ? _currentPosition?.longitude : null,
        notifyOthers: _notifyOthers,
      );

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('게시글이 등록됐습니다!')),
        );
        Navigator.pop(context, true);
      }
    } catch (e) {
      debugPrint('[FlowerSpot] 게시 실패: $e');
      if (mounted) {
        setState(() => _isPosting = false);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('게시 실패. 다시 시도해주세요.')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    return Scaffold(
      backgroundColor: colors.background,
      appBar: AppBar(
        backgroundColor: Colors.white, elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new, color: colors.primary, size: 18),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text('꽃 사진 올리기', style: TextStyle(color: colors.primary, fontWeight: FontWeight.bold)),
        actions: [
          TextButton(
            onPressed: _isPosting ? null : _submit,
            child: Text('게시', style: TextStyle(
              color: _isPosting ? Colors.grey : colors.primary,
              fontWeight: FontWeight.bold, fontSize: 16,
            )),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildImageSection(colors),
            const SizedBox(height: 16),
            if (_plantName != null) _buildPlantResult(colors),
            const SizedBox(height: 16),
            _buildContentInput(colors),
            const SizedBox(height: 16),
            _buildLocationSection(colors),
            const SizedBox(height: 8),
            if (_shareLocation) _buildNotifySection(colors),
          ],
        ),
      ),
    );
  }

  Widget _buildImageSection(SeasonColors colors) {
    return GestureDetector(
      onTap: _takePicture,
      child: Container(
        width: double.infinity, height: 240,
        decoration: BoxDecoration(
          color: colors.primary.withAlpha(20),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: colors.primary.withAlpha(60), width: 2),
        ),
        child: _image != null
            ? Stack(
                fit: StackFit.expand,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(18),
                    child: Image.file(_image!, fit: BoxFit.cover,
                      cacheWidth: 1080, filterQuality: FilterQuality.medium),
                  ),
                  if (_isIdentifying)
                    Container(
                      decoration: BoxDecoration(
                        color: Colors.black.withAlpha(100),
                        borderRadius: BorderRadius.circular(18),
                      ),
                      child: Center(
                        child: Column(mainAxisSize: MainAxisSize.min, children: [
                          CircularProgressIndicator(color: Colors.white),
                          const SizedBox(height: 8),
                          const Text('식물 인식 중...', style: TextStyle(color: Colors.white)),
                        ]),
                      ),
                    ),
                ],
              )
            : Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                Icon(Icons.camera_alt_outlined, size: 48, color: colors.primary.withAlpha(150)),
                const SizedBox(height: 8),
                Text('카메라로 사진 찍기', style: TextStyle(color: colors.primary, fontWeight: FontWeight.w600)),
                if (!_shareLocation) ...[
                  const SizedBox(height: 4),
                  TextButton(
                    onPressed: _pickFromGallery,
                    child: Text('갤러리에서 선택', style: TextStyle(color: Colors.grey[500], fontSize: 13)),
                  ),
                ],
              ]),
      ),
    );
  }

  Widget _buildPlantResult(SeasonColors colors) {
    final isRecognized = _plantName != '기타';
    final confidence = _plantConfidence != null ? '(${(_plantConfidence! * 100).toInt()}%)' : '';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: isRecognized ? colors.primary.withAlpha(20) : Colors.grey.withAlpha(20),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(children: [
        Icon(isRecognized ? Icons.local_florist : Icons.help_outline,
          color: isRecognized ? colors.primary : Colors.grey, size: 20),
        const SizedBox(width: 8),
        Text(
          isRecognized ? '$_plantName $confidence' : '기타 (인식 불가)',
          style: TextStyle(
            color: isRecognized ? colors.primary : Colors.grey[600],
            fontWeight: FontWeight.w600,
          ),
        ),
      ]),
    );
  }

  Widget _buildContentInput(SeasonColors colors) {
    return TextField(
      controller: _contentController,
      maxLines: 3, maxLength: 200,
      decoration: InputDecoration(
        hintText: '이 꽃에 대해 한마디 (선택)',
        hintStyle: TextStyle(color: Colors.grey[400]),
        filled: true, fillColor: Colors.white,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
        contentPadding: const EdgeInsets.all(14),
      ),
    );
  }

  Widget _buildLocationSection(SeasonColors colors) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(14)),
      child: Row(children: [
        Icon(Icons.location_on_outlined, color: colors.primary, size: 22),
        const SizedBox(width: 8),
        const Expanded(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('현재 위치 공유', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
            Text('ON이면 지도에 표시, 갤러리 업로드 불가', style: TextStyle(fontSize: 11, color: Colors.grey)),
          ],
        )),
        Switch(
          value: _shareLocation,
          onChanged: _toggleLocationShare,
          activeColor: colors.primary,
        ),
      ]),
    );
  }

  Widget _buildNotifySection(SeasonColors colors) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(14)),
      child: Row(children: [
        Icon(Icons.notifications_outlined, color: colors.primary, size: 22),
        const SizedBox(width: 8),
        const Expanded(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('근처 사용자에게 알림', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
            Text('반경 내 알림 수신 동의 사용자에게 전송', style: TextStyle(fontSize: 11, color: Colors.grey)),
          ],
        )),
        Switch(
          value: _notifyOthers,
          onChanged: (v) => setState(() => _notifyOthers = v),
          activeColor: colors.primary,
        ),
      ]),
    );
  }
}
