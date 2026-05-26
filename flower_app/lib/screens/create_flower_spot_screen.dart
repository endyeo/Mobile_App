import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:geolocator/geolocator.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/flower_spot_api_service.dart';
import '../utils/location_permission_helper.dart';
import '../widgets/chat_floating_button.dart';

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
  void initState() {
    super.initState();
    // 안드로이드: 카메라 인텐트 중 OS가 우리 액티비티를 메모리 회수했다가
    // 복귀하면 onActivityResult를 못 받음 → LostDataResponse로 복구.
    _retrieveLostCameraImage();
  }

  @override
  void dispose() {
    _contentController.dispose();
    super.dispose();
  }

  Future<void> _retrieveLostCameraImage() async {
    try {
      final LostDataResponse response = await ImagePicker().retrieveLostData();
      if (response.isEmpty) return;
      final XFile? file = response.file;
      if (file == null) return;
      final picked = File(file.path);
      if (!mounted) return;
      setState(() {
        _image = picked;
        _imageFromGallery = false;
        _capturedPosition = null;
        _plantName = null;
        _plantConfidence = null;
      });
      await _identifyPlant(picked);
    } catch (e) {
      debugPrint('[ImagePicker] lost data 복구 실패: $e');
    }
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
    // 토글 상태와 무관하게 GPS 캡처 시도 — 권한 없으면 조용히 null.
    // 게시 시점에 토글 ON + GPS 있음일 때만 서버로 전송한다.
    final Position? capturedPos = await _captureCurrentPositionIfAllowed();

    setState(() {
      _image = file;
      _imageFromGallery = false; // 카메라 촬영
      _capturedPosition = capturedPos;
      _plantName = null;
      _plantConfidence = null;
    });
    // 토글 ON 상태에서 2회 다 outlier로 실패한 경우 토글 자동 OFF + 안내
    if (capturedPos == null) {
      _handleLocationCaptureFailureIfSharing();
    }
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

  /// stream 1~2초 안정화 + outlier 거부.
  /// accuracy ≤ 15m 들어오면 즉시 완료, 8초까지 못 받으면 가장 좋은 거 채택.
  /// 가장 좋은 fix도 accuracy > 200m이면 outlier로 판단해 null 반환.
  Future<Position?> _captureBestPosition({
    Duration timeout = const Duration(seconds: 8),
    double goodEnoughAccuracy = 15.0,
    double maxAcceptableAccuracy = 200.0,
  }) async {
    Position? best;
    final completer = Completer<Position?>();
    StreamSubscription<Position>? sub;
    Timer? timer;

    void finish() {
      sub?.cancel();
      timer?.cancel();
      if (completer.isCompleted) return;
      if (best == null || best!.accuracy > maxAcceptableAccuracy) {
        completer.complete(null);
      } else {
        completer.complete(best);
      }
    }

    timer = Timer(timeout, finish);

    try {
      sub =
          Geolocator.getPositionStream(
            locationSettings: const LocationSettings(
              accuracy: LocationAccuracy.bestForNavigation,
              distanceFilter: 0,
            ),
          ).listen(
            (pos) {
              if (best == null || pos.accuracy < best!.accuracy) {
                best = pos;
              }
              if (pos.accuracy <= goodEnoughAccuracy) {
                finish();
              }
            },
            onError: (e) {
              debugPrint('[FlowerSpot] GPS stream 오류: $e');
              finish();
            },
          );
    } catch (e) {
      debugPrint('[FlowerSpot] GPS stream 시작 실패: $e');
      finish();
    }

    return completer.future;
  }

  /// 자동 1회 재시도. 둘 다 실패 시 null.
  Future<Position?> _captureWithRetry() async {
    Position? pos = await _captureBestPosition();
    if (pos != null) return pos;
    debugPrint('[FlowerSpot] GPS 첫 시도 outlier — 재시도');
    await Future.delayed(const Duration(seconds: 1));
    return await _captureBestPosition();
  }

  /// 권한이 이미 허용된 경우에만 GPS를 잡는다.
  /// 권한이 없는 상태에서 매번 사용자에게 권한 다이얼로그를 띄우지 않기 위함.
  /// 사용자가 위치 공유 토글을 ON으로 켤 때 권한 요청을 진행한다.
  Future<Position?> _captureCurrentPositionIfAllowed() async {
    try {
      final LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        return null;
      }
      return await _captureWithRetry();
    } catch (_) {
      return null;
    }
  }

  /// 위치 공유 ON 상태에서 GPS 2회 모두 outlier면 토글 OFF + 안내.
  void _handleLocationCaptureFailureIfSharing() {
    if (!mounted || !_shareLocation) return;
    setState(() => _shareLocation = false);
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('GPS 정확도가 낮아 위치 공유를 해제했어요. 잠시 후 다시 시도해주세요.'),
        duration: Duration(seconds: 3),
      ),
    );
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

        setState(() {
          _shareLocation = true;
          // 갤러리 사진 → 위치 검증 불가 → 제거 (위치 공유 시엔 카메라만)
          if (_imageFromGallery) {
            _image = null;
            _imageFromGallery = false;
            _capturedPosition = null;
            _plantName = null;
            _plantConfidence = null;
          }
        });

        // 카메라 사진은 이미 찍힐 때 GPS도 같이 잡혀있을 수 있음 → 그대로 유지.
        // GPS가 없는 경우 게시 시점에 안내한다.

        if (mounted && hadGalleryImage) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('위치 공유 시 카메라로 촬영해주세요.')));
        }
      } catch (e) {
        if (mounted) _showLocationDeniedDialog();
      }
    } else {
      setState(() {
        _shareLocation = false;
        // capturedPosition은 비우지 않음 — 다시 토글 ON 시 그대로 사용 가능
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
    // 게시 직전 anti-cheating용 — 안정화된 fix 1회만 (재시도 없이)
    final Position? now = await _captureBestPosition();
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
    // 토글 ON 인데 사진 찍은 시점의 GPS가 없으면 안내만 표시하고 위치 없이 진행
    if (_shareLocation && _capturedPosition == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('위치를 잡지 못해 위치 공유 없이 등록됩니다.')),
      );
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
      floatingActionButton: const ChatFloatingButton(),
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
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
                  'ON일 때만 사진 찍은 위치가 서버로 전송 (갤러리 사진은 비공유)',
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
