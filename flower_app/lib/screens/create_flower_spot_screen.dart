import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:geolocator/geolocator.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/flower_spot_api_service.dart';
import '../utils/location_permission_helper.dart';

class CreateFlowerSpotScreen extends StatefulWidget {
  const CreateFlowerSpotScreen({super.key});

  @override
  State<CreateFlowerSpotScreen> createState() => _CreateFlowerSpotScreenState();
}

class _CreateFlowerSpotScreenState extends State<CreateFlowerSpotScreen> {
  // 게시 시점 GPS와 비교해 너무 멀리 이동했으면 경고 (m)
  static const double _maxMoveAfterCaptureMeters = 500;

  File? _image;
  bool _imageFromGallery = false; // 갤러리 출처 여부
  bool _shareLocation = false;
  bool _notifyOthers = false;
  bool _isIdentifying = false;
  bool _isPosting = false;
  String? _plantName;
  double? _plantConfidence;
  Position? _capturedPosition; // 사진 찍는 순간의 GPS
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
      requestFullMetadata: false,
    );
    if (picked == null) return;

    final file = File(picked.path);
    Position? capturedPos;
    if (_shareLocation) {
      capturedPos = await _captureCurrentPosition();
      if (capturedPos == null && mounted) {
        // GPS 못 잡았으면 위치 공유 강제 해제 (가짜 위치 등록 차단)
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('위치를 잡지 못해 위치 공유 없이 등록됩니다.')),
        );
      }
    }

    setState(() {
      _image = file;
      _imageFromGallery = false; // 카메라 촬영
      _capturedPosition = capturedPos;
      if (capturedPos == null) _shareLocation = false;
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
      requestFullMetadata: false,
    );
    if (picked == null) return;

    final file = File(picked.path);
    setState(() {
      _image = file;
      _imageFromGallery = true; // 갤러리 선택 → 위치 검증 불가
      _capturedPosition = null;
      _plantName = null;
      _plantConfidence = null;
    });
    await _identifyPlant(file);
  }

  Future<Position?> _captureCurrentPosition() async {
    try {
      return await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.best,
        ),
      ).timeout(const Duration(seconds: 8));
    } catch (e) {
      debugPrint('[FlowerSpot] 사진 시점 GPS 캡처 실패: $e');
      return null;
    }
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
      if (mounted)
        setState(() {
          _plantName = '기타';
          _isIdentifying = false;
        });
    }
  }

  Future<void> _toggleLocationShare(bool value) async {
    if (value) {
      // 위치 공유는 권한 체크만. 실제 GPS는 사진 찍을 때 캡처.
      try {
        LocationPermission permission = await Geolocator.checkPermission();
        if (permission == LocationPermission.denied) {
          permission = await Geolocator.requestPermission();
        }
        if (permission == LocationPermission.deniedForever) {
          if (mounted) _showLocationDeniedDialog();
          return;
        }
        if (mounted) await promptAlwaysLocation(context);

        final hadGalleryImage = _imageFromGallery;
        final hadCameraImageNoGps =
            _image != null && !_imageFromGallery && _capturedPosition == null;

        setState(() {
          _shareLocation = true;
          // 갤러리 사진 → 위치 검증 불가 → 제거
          // 카메라 사진인데 촬영 시점 GPS가 없으면 → 다시 찍어야 함
          if (_imageFromGallery || hadCameraImageNoGps) {
            _image = null;
            _imageFromGallery = false;
            _capturedPosition = null;
            _plantName = null;
            _plantConfidence = null;
          }
        });

        if (mounted) {
          if (hadGalleryImage) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('위치 공유 시 카메라로 촬영해주세요.')),
            );
          } else if (hadCameraImageNoGps) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('위치 기록을 위해 사진을 다시 촬영해주세요.')),
            );
          }
        }
      } catch (e) {
        if (mounted) _showLocationDeniedDialog();
      }
    } else {
      setState(() {
        _shareLocation = false;
        _capturedPosition = null;
        _notifyOthers = false;
      });
    }
  }

  void _showLocationDeniedDialog() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('위치 권한 필요'),
        content: const Text('위치 공유를 위해 위치 권한이 필요합니다.\n설정에서 허용해주세요.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('확인'),
          ),
        ],
      ),
    );
  }

  Future<bool> _confirmIfMovedFar(Position captured) async {
    final Position? now = await _captureCurrentPosition();
    if (now == null) return true; // 현재 위치 못 잡았으면 검증 생략(촬영 위치 신뢰)
    final double meters = Geolocator.distanceBetween(
      captured.latitude,
      captured.longitude,
      now.latitude,
      now.longitude,
    );
    if (meters < _maxMoveAfterCaptureMeters) return true;

    if (!mounted) return false;
    final bool? proceed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('촬영 위치 확인'),
        content: Text(
          '사진 찍은 위치에서 약 ${meters.toStringAsFixed(0)}m 이동한 상태입니다.\n'
          '게시되는 위치는 사진을 찍은 곳이에요. 그대로 등록할까요?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('등록'),
          ),
        ],
      ),
    );
    return proceed ?? false;
  }

  Future<void> _submit() async {
    if (_image == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('사진을 먼저 촬영해주세요.')));
      return;
    }
    if (_plantName == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('식물 인식 중입니다. 잠시 기다려주세요.')));
      return;
    }
    if (_shareLocation && _capturedPosition == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('위치 공유를 위해 카메라로 사진을 다시 찍어주세요.')),
      );
      return;
    }

    // 사진 찍은 위치에서 멀리 이동했으면 확인 다이얼로그
    if (_shareLocation && _capturedPosition != null) {
      final ok = await _confirmIfMovedFar(_capturedPosition!);
      if (!ok) return;
    }

    setState(() => _isPosting = true);
    try {
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString('accessToken') ?? '';

      await FlowerSpotApiService.createFlowerSpot(
        accessToken: token,
        image: _image!,
        content: _contentController.text.trim().isEmpty
            ? null
            : _contentController.text.trim(),
        plantName: _plantName!,
        plantConfidence: _plantConfidence?.toDouble() ?? 0.0,
        latitude: _shareLocation ? _capturedPosition?.latitude : null,
        longitude: _shareLocation ? _capturedPosition?.longitude : null,
        notifyOthers: _notifyOthers,
      );

      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('게시글이 등록됐습니다!')));
        Navigator.pop(context, true);
      }
    } catch (e) {
      debugPrint('[FlowerSpot] 게시 실패: $e');
      if (mounted) {
        setState(() => _isPosting = false);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('게시 실패. 다시 시도해주세요.')));
      }
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
          '꽃 사진 올리기',
          style: TextStyle(color: colors.primary, fontWeight: FontWeight.bold),
        ),
        actions: [
          TextButton(
            onPressed: _isPosting ? null : _submit,
            child: Text(
              '게시',
              style: TextStyle(
                color: _isPosting ? Colors.grey : colors.primary,
                fontWeight: FontWeight.bold,
                fontSize: 16,
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
        width: double.infinity,
        height: 240,
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
                    child: Image.file(
                      _image!,
                      fit: BoxFit.cover,
                      cacheWidth: 1080,
                      filterQuality: FilterQuality.medium,
                    ),
                  ),
                  if (_isIdentifying)
                    Container(
                      decoration: BoxDecoration(
                        color: Colors.black.withAlpha(100),
                        borderRadius: BorderRadius.circular(18),
                      ),
                      child: Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            CircularProgressIndicator(color: Colors.white),
                            const SizedBox(height: 8),
                            const Text(
                              '식물 인식 중...',
                              style: TextStyle(color: Colors.white),
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              )
            : Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.camera_alt_outlined,
                    size: 48,
                    color: colors.primary.withAlpha(150),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '카메라로 사진 찍기',
                    style: TextStyle(
                      color: colors.primary,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (!_shareLocation) ...[
                    const SizedBox(height: 4),
                    TextButton(
                      onPressed: _pickFromGallery,
                      child: Text(
                        '갤러리에서 선택',
                        style: TextStyle(color: Colors.grey[500], fontSize: 13),
                      ),
                    ),
                  ],
                ],
              ),
      ),
    );
  }

  Widget _buildPlantResult(SeasonColors colors) {
    final isRecognized = _plantName != '기타';
    final confidence = _plantConfidence != null
        ? '(${(_plantConfidence! * 100).toInt()}%)'
        : '';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: isRecognized
            ? colors.primary.withAlpha(20)
            : Colors.grey.withAlpha(20),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(
            isRecognized ? Icons.local_florist : Icons.help_outline,
            color: isRecognized ? colors.primary : Colors.grey,
            size: 20,
          ),
          const SizedBox(width: 8),
          Text(
            isRecognized ? '$_plantName $confidence' : '기타 (인식 불가)',
            style: TextStyle(
              color: isRecognized ? colors.primary : Colors.grey[600],
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildContentInput(SeasonColors colors) {
    return TextField(
      controller: _contentController,
      maxLines: 3,
      maxLength: 200,
      decoration: InputDecoration(
        hintText: '이 꽃에 대해 한마디 (선택)',
        hintStyle: TextStyle(color: Colors.grey[400]),
        filled: true,
        fillColor: Colors.white,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide.none,
        ),
        contentPadding: const EdgeInsets.all(14),
      ),
    );
  }

  Widget _buildLocationSection(SeasonColors colors) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          Icon(Icons.location_on_outlined, color: colors.primary, size: 22),
          const SizedBox(width: 8),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '촬영 위치 공유',
                  style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
                ),
                Text(
                  '사진 찍은 그 위치가 기록됨 (갤러리 불가)',
                  style: TextStyle(fontSize: 11, color: Colors.grey),
                ),
              ],
            ),
          ),
          Switch(
            value: _shareLocation,
            onChanged: _toggleLocationShare,
            activeColor: colors.primary,
          ),
        ],
      ),
    );
  }

  Widget _buildNotifySection(SeasonColors colors) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          Icon(Icons.notifications_outlined, color: colors.primary, size: 22),
          const SizedBox(width: 8),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '근처 사용자에게 알림',
                  style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
                ),
                Text(
                  '반경 내 알림 수신 동의 사용자에게 전송',
                  style: TextStyle(fontSize: 11, color: Colors.grey),
                ),
              ],
            ),
          ),
          Switch(
            value: _notifyOthers,
            onChanged: (v) => setState(() => _notifyOthers = v),
            activeColor: colors.primary,
          ),
        ],
      ),
    );
  }
}
