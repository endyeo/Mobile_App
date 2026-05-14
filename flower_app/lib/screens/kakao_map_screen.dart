import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../api_config.dart';
import '../models/chat_action.dart';
import '../services/tour_api_service.dart';
import '../theme/season_theme.dart';
import '../widgets/app_bottom_navigation.dart';
import '../widgets/chat_floating_button.dart';
import '../widgets/map_html_view.dart';

class KakaoMapScreen extends StatefulWidget {
  const KakaoMapScreen({
    super.key,
    this.isEmbedded = false,
    this.initialActions,
    this.initialFestival,
  });

  final bool isEmbedded;
  final List<ChatAction>? initialActions;
  final FestivalData? initialFestival;

  @override
  State<KakaoMapScreen> createState() => KakaoMapScreenState();
}

class KakaoMapScreenState extends State<KakaoMapScreen> {
  final TextEditingController _searchController = TextEditingController();
  final TourApiService _tourApiService = TourApiService();
  final String _webMapViewType = 'flower-map-${identityHashCode(Object())}';

  WebViewController? _controller;
  String? _mapHtml;
  bool _isLoading = true;
  bool _showFlowerMarkers = true;
  bool _showFestivalMarkers = true;
  bool _isLoadingFestivals = true;
  String? _errorMessage;
  String? _festivalError;
  Position? _currentPosition;
  List<FestivalData> _festivals = <FestivalData>[];

  @override
  void initState() {
    super.initState();
    if (!kIsWeb) {
      _configureWebViewController();
    }
    _initializeFestivalState();
    _loadMapHtml();
  }

  Future<void> _initializeFestivalState() async {
    await _captureCurrentLocation();
    await _loadFestivalData();
  }

  void _configureWebViewController() {
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.transparent)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageFinished: (_) {
            if (!mounted) return;
            setState(() => _isLoading = false);
            _syncMapState();
          },
          onWebResourceError: (WebResourceError error) {
            if (!mounted || error.isForMainFrame != true) return;
            setState(() {
              _isLoading = false;
              _errorMessage = error.description;
            });
          },
          onNavigationRequest: (NavigationRequest request) {
            final Uri? uri = Uri.tryParse(request.url);
            if (uri != null && _shouldOpenExternally(uri)) {
              _openExternal(uri);
              return NavigationDecision.prevent;
            }
            return NavigationDecision.navigate;
          },
        ),
      );
  }

  Future<void> _loadFestivalData() async {
    try {
      final List<FestivalData> festivals = await _tourApiService
          .getFlowerFestivals();
      if (!mounted) return;
      setState(() {
        _festivals = _sortFestivals(festivals, _currentPosition);
        _isLoadingFestivals = false;
        _festivalError = null;
      });
      await _syncFestivalDataToMap();
      await _focusInitialFestivalIfNeeded();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isLoadingFestivals = false;
        _festivalError = error.toString();
      });
    }
  }

  Future<void> _captureCurrentLocation() async {
    try {
      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        return;
      }

      final Position position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
        ),
      );

      if (!mounted) return;
      setState(() {
        _currentPosition = position;
        _festivals = _sortFestivals(_festivals, position);
      });
    } catch (_) {
      // Keep the list available even when location access is not granted.
    }
  }

  Future<void> _loadMapHtml() async {
    try {
      final bool useAndroidHost =
          defaultTargetPlatform == TargetPlatform.android;
      final Map<String, String> mapConfig = <String, String>{
        'kakaoKey': ApiConfig.kakaoMapKey,
        'apiBaseUrl': ApiConfig.mapApiBaseUrl(androidEmulator: useAndroidHost),
        'centerLat': '37.5665',
        'centerLng': '126.9780',
        'zoom': '5',
        'radius': '5000',
        'maxRadius': '50000',
        'limit': '50',
        'embedded': widget.isEmbedded ? '1' : '0',
        'tourApiKey': ApiConfig.tourApiKey,
      };

      if (kIsWeb) {
        setState(() {
          _mapHtml = _buildWebMapAssetUrl(mapConfig);
          _isLoading = false;
        });
        return;
      }

      if (_controller == null) return;
      final String style = await rootBundle.loadString('assets/map/style.css');
      final String app = await rootBundle.loadString('assets/map/app.js');
      await _controller!.loadHtmlString(
        _buildNativeMapHtml(mapConfig, style, app),
        baseUrl: 'https://ourt.kro.kr/map/',
      );
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _errorMessage = error.toString();
      });
    }
  }

  String _buildWebMapAssetUrl(Map<String, String> mapConfig) {
    return 'assets/assets/map/index.html#${Uri(queryParameters: mapConfig).query}';
  }

  String _buildNativeMapHtml(
    Map<String, String> mapConfig,
    String style,
    String app,
  ) {
    final Map<String, Object> config = <String, Object>{
      'KAKAO_APP_KEY': mapConfig['kakaoKey'] ?? '',
      'TOUR_API_KEY': mapConfig['tourApiKey'] ?? '',
      'API_BASE_URL': mapConfig['apiBaseUrl'] ?? '',
      'DEFAULT_CENTER': <String, double>{
        'lat': double.parse(mapConfig['centerLat'] ?? '37.5665'),
        'lng': double.parse(mapConfig['centerLng'] ?? '126.9780'),
      },
      'DEFAULT_ZOOM_LEVEL': double.parse(mapConfig['zoom'] ?? '5'),
      'DEFAULT_RADIUS': double.parse(mapConfig['radius'] ?? '5000'),
      'MAX_RADIUS': double.parse(mapConfig['maxRadius'] ?? '50000'),
      'DEFAULT_LIMIT': double.parse(mapConfig['limit'] ?? '50'),
      'EMBEDDED': mapConfig['embedded'] == '1',
    };
    final String configJson = jsonEncode(config);

    return '''
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>OurT 지도</title>
  <style>
$style
  </style>
  <script>
    window.MAP_CONFIG = $configJson;
    (function () {
      var kakaoKey = window.MAP_CONFIG.KAKAO_APP_KEY || '';
      if (!kakaoKey || kakaoKey === 'YOUR_KAKAO_JS_KEY_HERE') {
        return;
      }

      var script = document.createElement('script');
      script.src = 'https://dapi.kakao.com/v2/maps/sdk.js?appkey='
        + encodeURIComponent(kakaoKey)
        + '&libraries=services&autoload=false';
      script.onload = function () {
        if (window.kakao && window.kakao.maps) {
          window.kakao.maps.load(function () {
            document.dispatchEvent(new Event('kakao-map-ready'));
          });
        }
      };
      script.onerror = function () {
        document.dispatchEvent(new Event('kakao-map-error'));
      };
      document.head.appendChild(script);
    })();
  </script>
</head>
<body>
  <main id="map-shell">
    <section id="map"></section>
  </main>
  <script>
$app
  </script>
</body>
</html>
''';
  }

  Future<void> _syncMapState() async {
    await _applyInitialActions();
    await _syncFestivalDataToMap();
    await _syncCategoryState();
    await _focusInitialFestivalIfNeeded();
  }

  Future<void> _applyInitialActions() async {
    final List<ChatAction>? actions = widget.initialActions;
    if (actions == null || actions.isEmpty) return;

    for (final ChatAction action in actions) {
      if (action.type != 'MAP_SET_SEARCH_QUERY') continue;
      final String query =
          action.params?['query'] as String? ??
          action.params?['q'] as String? ??
          '';
      if (query.isEmpty) continue;
      _searchController.text = query;
      await setSearchQuery(query);
    }
  }

  Future<void> _syncFestivalDataToMap() async {
    if (kIsWeb || _controller == null || _festivals.isEmpty) return;
    final String payload = jsonEncode(
      _festivals
          .map((FestivalData festival) => festival.toMapPayload())
          .toList(),
    );
    await _controller!.runJavaScript(
      'if(window.FlowerMap) window.FlowerMap.setFestivals($payload);',
    );
  }

  Future<void> _syncCategoryState() async {
    if (kIsWeb || _controller == null) return;
    await _controller!.runJavaScript(
      'if(window.FlowerMap) window.FlowerMap.setVisibleCategories('
      '${_showFlowerMarkers ? 'true' : 'false'},'
      '${_showFestivalMarkers ? 'true' : 'false'});',
    );
  }

  Future<void> _focusInitialFestivalIfNeeded() async {
    if (widget.initialFestival == null) return;
    await _focusFestival(widget.initialFestival!);
  }

  Future<void> setSearchQuery(String query) async {
    final String escaped = query.replaceAll(r'\', r'\\').replaceAll("'", r"\'");
    await _controller?.runJavaScript(
      "if(window.FlowerMap) window.FlowerMap.setSearchQuery('$escaped');",
    );
  }

  Future<void> zoomIn() async {
    await _controller?.runJavaScript(
      'if(window.FlowerMap) window.FlowerMap.zoomIn();',
    );
  }

  Future<void> zoomOut() async {
    await _controller?.runJavaScript(
      'if(window.FlowerMap) window.FlowerMap.zoomOut();',
    );
  }

  Future<void> moveToCurrentLocation() async {
    try {
      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.deniedForever) return;

      final Position position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
        ),
      );
      if (!mounted) return;

      setState(() {
        _currentPosition = position;
        _festivals = _sortFestivals(_festivals, position);
      });

      await _controller?.runJavaScript(
        'if(window.FlowerMap) window.FlowerMap.setCurrentPosition('
        '${position.latitude},${position.longitude});',
      );
    } catch (error) {
      debugPrint('[GPS] Failed to move to current location: $error');
    }
  }

  Future<void> _focusFestival(FestivalData festival) async {
    if (kIsWeb || _controller == null) return;
    final String payload = jsonEncode(festival.toMapPayload());
    await _controller!.runJavaScript(
      'if(window.FlowerMap && typeof window.FlowerMap.focusFestival === "function") {'
      'window.FlowerMap.focusFestival($payload);'
      '}',
    );
  }

  bool _shouldOpenExternally(Uri uri) {
    return uri.host.contains('map.kakao.com') ||
        uri.scheme == 'kakaomap' ||
        uri.scheme == 'intent';
  }

  Future<void> _openExternal(Uri uri) async {
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  List<FestivalData> _sortFestivals(
    List<FestivalData> festivals,
    Position? position,
  ) {
    final List<FestivalData> copy = List<FestivalData>.from(festivals);
    if (position == null) {
      copy.sort((FestivalData a, FestivalData b) {
        final String aPeriod = a.periodString;
        final String bPeriod = b.periodString;
        if (aPeriod.isEmpty && bPeriod.isEmpty) return 0;
        if (aPeriod.isEmpty) return 1;
        if (bPeriod.isEmpty) return -1;
        return aPeriod.compareTo(bPeriod);
      });
      return copy;
    }

    copy.sort((FestivalData a, FestivalData b) {
      final double aDistance = a.distanceFrom(
        latitude: position.latitude,
        longitude: position.longitude,
      );
      final double bDistance = b.distanceFrom(
        latitude: position.latitude,
        longitude: position.longitude,
      );
      return aDistance.compareTo(bDistance);
    });
    return copy;
  }

  String _festivalDistanceLabel(FestivalData festival) {
    final Position? position = _currentPosition;
    if (position == null) return '';
    final double distance = festival.distanceFrom(
      latitude: position.latitude,
      longitude: position.longitude,
    );
    if (distance >= 1000) {
      return '${(distance / 1000).toStringAsFixed(1)}km';
    }
    return '${distance.round()}m';
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final SeasonColors colors = SeasonTheme.getColors();

    if (widget.isEmbedded) {
      return _buildMapBody(colors);
    }

    return Scaffold(
      backgroundColor: Colors.white,
      body: Stack(
        children: <Widget>[
          Positioned.fill(child: _buildMapBody(colors)),
          _buildTopBar(colors),
          _buildZoomControls(colors),
          _buildFestivalOverlay(colors),
        ],
      ),
      floatingActionButton: const ChatFloatingButton(),
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
      bottomNavigationBar: const AppBottomNavigation(currentTab: AppNavTab.map),
    );
  }

  Widget _buildTopBar(SeasonColors colors) {
    return Positioned(
      top: 0,
      left: 0,
      right: 0,
      child: SafeArea(
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          padding: const EdgeInsets.fromLTRB(10, 10, 10, 12),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.92),
            borderRadius: BorderRadius.circular(22),
            boxShadow: <BoxShadow>[
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.08),
                blurRadius: 12,
                offset: const Offset(0, 3),
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Row(
                children: <Widget>[
                  IconButton(
                    icon: Icon(
                      Icons.arrow_back_ios_new,
                      color: colors.primary,
                      size: 18,
                    ),
                    tooltip: '뒤로가기',
                    onPressed: () => Navigator.pop(context),
                  ),
                  Expanded(
                    child: TextField(
                      controller: _searchController,
                      textInputAction: TextInputAction.search,
                      decoration: const InputDecoration(
                        hintText: '꽃 이름, 종류 또는 주소',
                        border: InputBorder.none,
                        isDense: true,
                        contentPadding: EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 12,
                        ),
                      ),
                      onSubmitted: (String value) {
                        setSearchQuery(value.trim());
                      },
                    ),
                  ),
                  IconButton(
                    icon: Icon(Icons.search, color: colors.primary),
                    tooltip: '검색',
                    onPressed: () =>
                        setSearchQuery(_searchController.text.trim()),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Row(
                children: <Widget>[
                  _buildCategoryChip(
                    colors: colors,
                    label: '꽃',
                    selected: _showFlowerMarkers,
                    onTap: () async {
                      setState(() => _showFlowerMarkers = !_showFlowerMarkers);
                      await _syncCategoryState();
                    },
                  ),
                  const SizedBox(width: 8),
                  _buildCategoryChip(
                    colors: colors,
                    label: '축제',
                    selected: _showFestivalMarkers,
                    onTap: () async {
                      setState(
                        () => _showFestivalMarkers = !_showFestivalMarkers,
                      );
                      await _syncCategoryState();
                    },
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCategoryChip({
    required SeasonColors colors,
    required String label,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        decoration: BoxDecoration(
          color: selected
              ? colors.primary.withValues(alpha: 0.14)
              : Colors.grey.shade100,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color: selected
                ? colors.primary.withValues(alpha: 0.38)
                : Colors.grey.shade300,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Icon(
              selected ? Icons.check_circle : Icons.circle_outlined,
              size: 16,
              color: selected ? colors.primary : Colors.grey.shade500,
            ),
            const SizedBox(width: 6),
            Text(
              label,
              style: TextStyle(
                color: selected ? colors.primary : Colors.grey.shade700,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildZoomControls(SeasonColors colors) {
    return Positioned(
      top: 132,
      right: 16,
      child: SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            _mapControlButton(
              colors: colors,
              icon: Icons.add,
              tooltip: '확대',
              onTap: zoomIn,
            ),
            const SizedBox(height: 8),
            _mapControlButton(
              colors: colors,
              icon: Icons.remove,
              tooltip: '축소',
              onTap: zoomOut,
            ),
            const SizedBox(height: 8),
            _mapControlButton(
              colors: colors,
              icon: Icons.my_location,
              tooltip: '현재 위치',
              onTap: moveToCurrentLocation,
            ),
          ],
        ),
      ),
    );
  }

  Widget _mapControlButton({
    required SeasonColors colors,
    required IconData icon,
    required String tooltip,
    required VoidCallback onTap,
  }) {
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.92),
            shape: BoxShape.circle,
            boxShadow: <BoxShadow>[
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.12),
                blurRadius: 8,
              ),
            ],
          ),
          child: Icon(icon, color: colors.primary, size: 22),
        ),
      ),
    );
  }

  Widget _buildFestivalOverlay(SeasonColors colors) {
    return Positioned(
      left: 12,
      right: 12,
      bottom: 88,
      child: IgnorePointer(
        ignoring: _isLoadingFestivals && _festivals.isEmpty,
        child: AnimatedSwitcher(
          duration: const Duration(milliseconds: 180),
          child: _buildFestivalContent(colors),
        ),
      ),
    );
  }

  Widget _buildFestivalContent(SeasonColors colors) {
    if (_isLoadingFestivals) {
      return _festivalContainer(
        colors: colors,
        child: const SizedBox(
          height: 96,
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    }

    if (_festivalError != null) {
      return _festivalContainer(
        colors: colors,
        child: SizedBox(
          height: 110,
          child: Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                '축제 목록을 불러오지 못했습니다.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.grey.shade700,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
        ),
      );
    }

    if (_festivals.isEmpty) {
      return _festivalContainer(
        colors: colors,
        child: SizedBox(
          height: 110,
          child: Center(
            child: Text(
              '표시할 축제가 없습니다.',
              style: TextStyle(
                color: Colors.grey.shade700,
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
      );
    }

    final List<FestivalData> visibleFestivals = _festivals.take(8).toList();

    return _festivalContainer(
      colors: colors,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Row(
            children: <Widget>[
              Text(
                '주변 축제',
                style: TextStyle(
                  color: colors.primary,
                  fontSize: 15,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                '${visibleFestivals.length}',
                style: TextStyle(
                  color: Colors.grey.shade600,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          SizedBox(
            height: 108,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: EdgeInsets.zero,
              itemCount: visibleFestivals.length,
              separatorBuilder: (_, __) => const SizedBox(width: 10),
              itemBuilder: (BuildContext context, int index) {
                final FestivalData festival = visibleFestivals[index];
                return _festivalCard(colors, festival);
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _festivalContainer({
    required SeasonColors colors,
    required Widget child,
  }) {
    return Container(
      key: ValueKey<String>(
        'festival-${_isLoadingFestivals}-${_festivals.length}-${_festivalError != null}',
      ),
      padding: const EdgeInsets.fromLTRB(14, 14, 98, 14),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.95),
        borderRadius: BorderRadius.circular(22),
        boxShadow: <BoxShadow>[
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.08),
            blurRadius: 14,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: child,
    );
  }

  Widget _festivalCard(SeasonColors colors, FestivalData festival) {
    final String period = festival.periodString;
    final String distance = _festivalDistanceLabel(festival);

    return GestureDetector(
      onTap: () => _focusFestival(festival),
      child: Container(
        width: 250,
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: colors.primary.withValues(alpha: 0.12)),
        ),
        child: Row(
          children: <Widget>[
            ClipRRect(
              borderRadius: const BorderRadius.horizontal(
                left: Radius.circular(18),
              ),
              child: festival.hasImage
                  ? Image.network(
                      festival.imageUrl,
                      width: 96,
                      height: double.infinity,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) {
                        return _festivalPlaceholder(colors);
                      },
                    )
                  : _festivalPlaceholder(colors),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: <Widget>[
                    Text(
                      festival.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    if (period.isNotEmpty) ...<Widget>[
                      const SizedBox(height: 6),
                      Text(
                        period,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: colors.primary,
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                    if (festival.fullAddress.isNotEmpty) ...<Widget>[
                      const SizedBox(height: 4),
                      Text(
                        festival.fullAddress,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: Colors.grey.shade600,
                          fontSize: 11,
                          height: 1.35,
                        ),
                      ),
                    ],
                    if (distance.isNotEmpty) ...<Widget>[
                      const SizedBox(height: 6),
                      Text(
                        distance,
                        style: TextStyle(
                          color: Colors.grey.shade500,
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _festivalPlaceholder(SeasonColors colors) {
    return Container(
      width: 96,
      height: double.infinity,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: <Color>[
            colors.primary.withValues(alpha: 0.18),
            Colors.orange.shade100,
          ],
        ),
      ),
      child: Icon(Icons.local_florist_rounded, color: colors.primary, size: 34),
    );
  }

  Widget _buildMapBody(SeasonColors colors) {
    return Stack(
      children: <Widget>[
        Positioned.fill(child: _buildMapContent()),
        if (_isLoading)
          Positioned.fill(
            child: ColoredBox(
              color: Colors.white.withAlpha(220),
              child: Center(
                child: CircularProgressIndicator(color: colors.primary),
              ),
            ),
          ),
        if (_errorMessage != null)
          Positioned.fill(
            child: ColoredBox(
              color: Colors.white,
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      Icon(Icons.map_outlined, size: 44, color: colors.primary),
                      const SizedBox(height: 12),
                      const Text(
                        '지도를 불러오지 못했습니다.',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        _errorMessage!,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: Colors.grey.shade600,
                          fontSize: 12,
                        ),
                      ),
                      const SizedBox(height: 16),
                      ElevatedButton(
                        onPressed: () {
                          setState(() {
                            _errorMessage = null;
                            _isLoading = true;
                          });
                          _loadMapHtml();
                        },
                        child: const Text('다시 시도'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildMapContent() {
    if (kIsWeb) {
      final String? html = _mapHtml;
      if (html == null) return const SizedBox.shrink();
      return buildMapHtmlView(
        viewType: _webMapViewType,
        html: html,
        isInteractive: !widget.isEmbedded,
      );
    }

    if (_controller == null) return const SizedBox.shrink();
    return WebViewWidget(controller: _controller!);
  }
}
