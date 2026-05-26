import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

import '../api_config.dart';
import '../models/chat_action.dart';
import '../services/local_saved_place_service.dart';
import '../services/tour_api_service.dart';
import '../theme/season_theme.dart';
import '../utils/location_permission_helper.dart';
import '../widgets/app_bottom_navigation.dart';
import '../widgets/chat_floating_button.dart';
import '../widgets/map_html_view.dart';

class KakaoMapScreen extends StatefulWidget {
  const KakaoMapScreen({
    super.key,
    this.isEmbedded = false,
    this.initialActions,
    this.initialFestival,
    this.initialSavedPlace,
  });

  final bool isEmbedded;
  final List<ChatAction>? initialActions;
  final FestivalData? initialFestival;
  final LocalSavedPlace? initialSavedPlace;

  @override
  State<KakaoMapScreen> createState() => KakaoMapScreenState();
}

class KakaoMapScreenState extends State<KakaoMapScreen> {
  static KakaoMapScreenState? activeState;

  static const double _defaultCenterLat = 37.5665;
  static const double _defaultCenterLng = 126.9780;
  final GlobalKey _topBarKey = GlobalKey();
  final TextEditingController _searchController = TextEditingController();
  final TourApiService _tourApiService = TourApiService();
  final String _webMapViewType = 'flower-map-${identityHashCode(Object())}';

  WebViewController? _controller;
  Widget? _nativeWebViewWidget;
  String? _mapHtml;
  String? _nativeMapStyle;
  String? _nativeMapAppScript;
  bool _isLoading = true;
  bool _showFlowerMarkers = true;
  bool _showFestivalMarkers = true;
  bool _showTouristMarkers = true;
  bool _isLoadingFestivals = true;
  bool _isFestivalPanelOpen = true;
  double _topBarHeight = 112;
  String? _errorMessage;
  String? _festivalError;
  Position? _currentPosition;
  List<FestivalData> _festivals = <FestivalData>[];
  List<TouristSpotData> _touristSpots = <TouristSpotData>[];

  bool get _hasActiveMapSearch => _searchController.text.trim().isNotEmpty;

  @override
  void initState() {
    super.initState();
    activeState = this;
    final String? initialQuery = _initialSearchQuery();
    if (initialQuery != null) {
      _searchController.text = initialQuery;
    }
    _searchController.addListener(() {
      if (mounted) setState(() {});
    });
    if (!kIsWeb) {
      _configureWebViewController();
    }
    _initializeScreen();
  }

  Future<void> _initializeScreen() async {
    await _captureCurrentLocation();
    await _loadMapHtml();
    await _loadFestivalData();
    await _loadTouristSpotData();
  }

  void _configureWebViewController() {
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.transparent)
      ..clearCache()
      ..clearLocalStorage()
      ..setOnConsoleMessage((JavaScriptConsoleMessage message) {
        debugPrint('[MapWebView] ${message.level.name}: ${message.message}');
      })
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (String url) {
            debugPrint('[MapWebView] page started: $url');
          },
          onPageFinished: (_) {
            debugPrint('[MapWebView] page finished');
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
      )
      ..addJavaScriptChannel(
        'SaveMarker',
        onMessageReceived: (JavaScriptMessage message) {
          _handleSaveMarkerMessage(message.message);
        },
      );

    if (_controller!.platform is AndroidWebViewController) {
      (_controller!.platform as AndroidWebViewController).setMixedContentMode(
        MixedContentMode.alwaysAllow,
      );
    }

    final PlatformWebViewWidgetCreationParams baseParams =
        PlatformWebViewWidgetCreationParams(
          controller: _controller!.platform,
          layoutDirection: TextDirection.ltr,
        );

    final PlatformWebViewWidgetCreationParams params =
        _controller!.platform is AndroidWebViewController
        ? AndroidWebViewWidgetCreationParams.fromPlatformWebViewWidgetCreationParams(
            baseParams,
            displayWithHybridComposition: true,
          )
        : baseParams;

    _nativeWebViewWidget = WebViewWidget.fromPlatformCreationParams(
      key: const ValueKey<String>('native-kakao-map-webview'),
      params: params,
    );
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
          accuracy: LocationAccuracy.best,
        ),
      );

      if (mounted) await promptAlwaysLocation(context);
      if (!mounted) return;
      setState(() {
        _currentPosition = _isKoreanMapPosition(position) ? position : null;
        _festivals = _sortFestivals(_festivals, _currentPosition);
        _touristSpots = _sortTouristSpots(_touristSpots, _currentPosition);
      });
    } catch (_) {
      // Keep fallback map behavior when location is unavailable.
    }
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

  Future<void> _loadTouristSpotData() async {
    try {
      final List<TouristSpotData> spots = await _tourApiService
          .getNationwideFlowerTouristSpots();
      if (!mounted) return;
      setState(() {
        _touristSpots = _sortTouristSpots(spots, _currentPosition);
      });
      await _syncTouristSpotDataToMap();
    } catch (error) {
      debugPrint('[TourAPI] Failed to load tourist spots: $error');
    }
  }

  Future<void> _loadMapHtml() async {
    try {
      final bool useAndroidHost =
          defaultTargetPlatform == TargetPlatform.android;
      final Position? position = _currentPosition;
      final Map<String, String> mapConfig = <String, String>{
        'kakaoKey': ApiConfig.kakaoMapKey,
        'apiBaseUrl': ApiConfig.mapApiBaseUrl(androidEmulator: useAndroidHost),
        'centerLat': '${position?.latitude ?? _defaultCenterLat}',
        'centerLng': '${position?.longitude ?? _defaultCenterLng}',
        'zoom': '5',
        'radius': '5000',
        'maxRadius': '50000',
        'limit': '50',
        'embedded': widget.isEmbedded ? '1' : '0',
        'tourApiKey': ApiConfig.tourApiKey,
      };
      final String? initialSearchQuery = _initialSearchQuery();
      final int? initialFlowerId = _initialFlowerId();
      if (initialSearchQuery != null) {
        mapConfig['initialSearchQuery'] = initialSearchQuery;
      }
      if (initialFlowerId != null) {
        mapConfig['initialFocusFlowerId'] = '$initialFlowerId';
        mapConfig['initialOpenFlowerPreview'] =
            _shouldOpenInitialFlowerPreview() ? '1' : '0';
      }
      final int? initialRouteFlowerId = _initialRouteFlowerId();
      if (initialRouteFlowerId != null) {
        mapConfig['initialRouteFlowerId'] = '$initialRouteFlowerId';
        final String? initialRouteMode = _initialRouteMode();
        if (initialRouteMode != null) {
          mapConfig['initialRouteMode'] = initialRouteMode;
        }
      }

      if (kIsWeb) {
        setState(() {
          _mapHtml = _buildWebMapAssetUrl(mapConfig);
          _isLoading = false;
        });
        return;
      }

      if (_controller == null) return;
      _nativeMapStyle ??= await rootBundle.loadString('assets/map/style.css');
      _nativeMapAppScript ??= await rootBundle.loadString('assets/map/app.js');
      final String html = _buildNativeMapHtml(
        mapConfig,
        _nativeMapStyle!,
        _nativeMapAppScript!,
      );
      await _controller!.loadHtmlString(
        html,
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
      'INITIAL_SEARCH_QUERY': mapConfig['initialSearchQuery'] ?? '',
      'INITIAL_FOCUS_FLOWER_ID': mapConfig['initialFocusFlowerId'] ?? '',
      'INITIAL_OPEN_FLOWER_PREVIEW':
          mapConfig['initialOpenFlowerPreview'] == '1',
      'INITIAL_ROUTE_FLOWER_ID': mapConfig['initialRouteFlowerId'] ?? '',
      'INITIAL_ROUTE_MODE': mapConfig['initialRouteMode'] ?? '',
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
        console.log('Kakao SDK script loaded');
        if (window.kakao && window.kakao.maps) {
          window.kakao.maps.load(function () {
            console.log('Kakao Maps ready event dispatched');
            document.dispatchEvent(new Event('kakao-map-ready'));
          });
        }
      };
      script.onerror = function () {
        console.error('Kakao SDK script failed to load');
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
    await _syncRoutePanelTop();
    await _syncCurrentPositionToMap();
    await _applyInitialActions();
    await _syncFestivalDataToMap();
    await _syncTouristSpotDataToMap();
    await _syncCategoryState();
    await _syncSavedPlacesToMap();
    await _focusInitialSavedPlaceIfNeeded();
    await _focusInitialFestivalIfNeeded();
  }

  Future<void> _syncCurrentPositionToMap() async {
    final Position? position = _currentPosition;
    if (kIsWeb || _controller == null || position == null) return;
    await _controller!.runJavaScript(
      'if(window.FlowerMap) window.FlowerMap.setCurrentPosition('
      '${position.latitude},${position.longitude});',
    );
  }

  Future<void> _applyInitialActions() async {
    final List<ChatAction>? actions = widget.initialActions;
    if (actions == null || actions.isEmpty) return;
    await applyChatActions(actions);
  }

  Future<void> applyChatActions(List<ChatAction> actions) async {
    for (final ChatAction action in actions) {
      if (action.type == 'MAP_SET_SEARCH_QUERY') {
        final String query =
            action.params?['query'] as String? ??
            action.params?['q'] as String? ??
            '';
        if (query.isEmpty) continue;
        _searchController.text = query;
        await setSearchQuery(query);
      } else if (action.type == 'MAP_SHOW_FLOWER' ||
          action.type == 'MAP_OPEN_FLOWER_PREVIEW') {
        final int? flowerId = _intParam(action, 'flowerId');
        if (flowerId == null) continue;
        await _focusFlowerById(
          flowerId,
          openPreview: action.type == 'MAP_OPEN_FLOWER_PREVIEW',
        );
      } else if (action.type == 'MAP_OPEN_ROUTE_CHOOSER') {
        final int? flowerId = _intParam(action, 'flowerId');
        if (flowerId == null) continue;
        await _openRouteChooserByFlowerId(flowerId);
      } else if (action.type == 'MAP_START_ROUTE') {
        final int? flowerId = _intParam(action, 'flowerId');
        final String? mode = action.params?['mode'] as String?;
        if (flowerId == null || mode == null || mode.trim().isEmpty) continue;
        await _startRouteToFlowerById(flowerId, mode.trim());
      }
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

  Future<void> _syncTouristSpotDataToMap() async {
    if (kIsWeb || _controller == null || _touristSpots.isEmpty) return;
    final String payload = jsonEncode(
      _touristSpots.map((TouristSpotData spot) => spot.toMapPayload()).toList(),
    );
    await _controller!.runJavaScript(
      'if(window.FlowerMap) window.FlowerMap.setTouristSpots($payload);',
    );
  }

  Future<void> _syncCategoryState() async {
    if (kIsWeb || _controller == null) return;
    await _controller!.runJavaScript(
      'if(window.FlowerMap) window.FlowerMap.setVisibleCategories('
      '${_showFlowerMarkers ? 'true' : 'false'},'
      '${_showFestivalMarkers ? 'true' : 'false'},'
      '${_showTouristMarkers ? 'true' : 'false'});',
    );
  }

  Future<void> _syncSavedPlacesToMap() async {
    if (kIsWeb || _controller == null) return;
    final Set<String> keys = await LocalSavedPlaceService.getSavedKeys();
    final String payload = jsonEncode(keys.toList());
    await _controller!.runJavaScript(
      'if(window.FlowerMap && typeof window.FlowerMap.setSavedPlaceKeys === "function") {'
      'window.FlowerMap.setSavedPlaceKeys($payload);'
      '}',
    );
  }

  Future<void> _syncRoutePanelTop() async {
    if (kIsWeb || _controller == null || !mounted || _isLoading) return;
    final double routePanelTop = widget.isEmbedded
        ? 16
        : _topOverlayStart(context);
    try {
      await _controller!.runJavaScript(
        'if(window.FlowerMap) window.FlowerMap.setRoutePanelTop('
        '${routePanelTop.toStringAsFixed(1)});',
      );
    } catch (error) {
      debugPrint('[MapWebView] route panel top sync failed: $error');
    }
  }

  Future<void> _focusInitialFestivalIfNeeded() async {
    if (widget.initialFestival == null) return;
    await _focusFestival(widget.initialFestival!);
  }

  Future<void> _focusInitialSavedPlaceIfNeeded() async {
    final LocalSavedPlace? place = widget.initialSavedPlace;
    if (place == null || kIsWeb || _controller == null) return;
    final String payload = jsonEncode(place.toJson());
    await _controller!.runJavaScript(
      'if(window.FlowerMap && typeof window.FlowerMap.focusSavedPlace === "function") {'
      'window.FlowerMap.focusSavedPlace($payload);'
      '}',
    );
  }

  Future<void> _handleSaveMarkerMessage(String rawMessage) async {
    try {
      final Object? decoded = jsonDecode(rawMessage);
      if (decoded is! Map<String, dynamic>) return;
      if (decoded['type'] != 'toggle_saved_place') return;
      final Object? payload = decoded['payload'];
      if (payload is! Map<String, dynamic>) return;
      final LocalSavedPlace place = LocalSavedPlace.fromMapPayload(payload);
      final bool saved = await LocalSavedPlaceService.togglePlace(place);
      await _syncSavedPlacesToMap();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(saved ? '저장됨에 추가했습니다' : '저장됨에서 삭제했습니다'),
          duration: const Duration(seconds: 1),
        ),
      );
    } catch (error) {
      debugPrint('[MapWebView] save marker failed: $error');
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('저장 처리에 실패했습니다'),
          duration: Duration(seconds: 1),
        ),
      );
    }
  }

  Future<void> setSearchQuery(String query) async {
    final String trimmed = query.trim();
    if (_searchController.text != trimmed) {
      _searchController.text = trimmed;
    }
    if (mounted) {
      setState(() {});
    }
    final String encoded = jsonEncode(trimmed);
    await _controller?.runJavaScript(
      'if(window.FlowerMap) window.FlowerMap.setSearchQuery($encoded);',
    );
  }

  Future<void> _focusFlowerById(
    int flowerId, {
    required bool openPreview,
  }) async {
    if (kIsWeb || _controller == null) return;
    await _controller!.runJavaScript(
      'if(window.FlowerMap && typeof window.FlowerMap.focusFlowerById === "function") {'
      'window.FlowerMap.focusFlowerById($flowerId, ${openPreview ? 'true' : 'false'});'
      '}',
    );
  }

  Future<void> _openRouteChooserByFlowerId(int flowerId) async {
    if (kIsWeb || _controller == null) return;
    await _controller!.runJavaScript(
      'if(window.FlowerMap && typeof window.FlowerMap.openRouteChooserByFlowerId === "function") {'
      'window.FlowerMap.openRouteChooserByFlowerId($flowerId);'
      '}',
    );
  }

  Future<void> _startRouteToFlowerById(int flowerId, String mode) async {
    if (kIsWeb || _controller == null) return;
    final String escapedMode = mode
        .replaceAll(r'\', r'\\')
        .replaceAll("'", r"\'");
    await _controller!.runJavaScript(
      'if(window.FlowerMap && typeof window.FlowerMap.startRouteToFlowerById === "function") {'
      "window.FlowerMap.startRouteToFlowerById($flowerId, '$escapedMode');"
      '}',
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
          accuracy: LocationAccuracy.best,
        ),
      );
      if (!mounted) return;

      setState(() {
        _currentPosition = _isKoreanMapPosition(position) ? position : null;
        _festivals = _sortFestivals(_festivals, _currentPosition);
        _touristSpots = _sortTouristSpots(_touristSpots, _currentPosition);
      });

      if (_currentPosition != null) {
        await _controller?.runJavaScript(
          'if(window.FlowerMap) window.FlowerMap.setCurrentPosition('
          '${position.latitude},${position.longitude});',
        );
      } else {
        await _controller?.runJavaScript(
          'if(window.FlowerMap) window.FlowerMap.resetToDefaultCenter();',
        );
      }
      setState(() {
        _touristSpots = _sortTouristSpots(_touristSpots, _currentPosition);
      });
      await _syncTouristSpotDataToMap();
    } catch (error) {
      debugPrint('[GPS] Failed to move to current location: $error');
    }
  }

  bool _isKoreanMapPosition(Position position) {
    return position.latitude >= 33.0 &&
        position.latitude <= 38.9 &&
        position.longitude >= 124.0 &&
        position.longitude <= 132.0;
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

  String? _initialSearchQuery() {
    final List<ChatAction>? actions = widget.initialActions;
    if (actions == null) return null;
    for (final ChatAction action in actions) {
      if (action.type != 'MAP_SET_SEARCH_QUERY') continue;
      final String query =
          action.params?['query'] as String? ??
          action.params?['q'] as String? ??
          '';
      final String trimmed = query.trim();
      if (trimmed.isNotEmpty) return trimmed;
    }
    return null;
  }

  int? _initialFlowerId() {
    final List<ChatAction>? actions = widget.initialActions;
    if (actions == null) return null;
    for (final ChatAction action in actions) {
      if (action.type != 'MAP_SHOW_FLOWER' &&
          action.type != 'MAP_OPEN_FLOWER_PREVIEW') {
        continue;
      }
      final int? flowerId = _intParam(action, 'flowerId');
      if (flowerId != null) return flowerId;
    }
    return null;
  }

  bool _shouldOpenInitialFlowerPreview() {
    final List<ChatAction>? actions = widget.initialActions;
    if (actions == null) return false;
    return actions.any(
      (ChatAction action) => action.type == 'MAP_OPEN_FLOWER_PREVIEW',
    );
  }

  int? _initialRouteFlowerId() {
    final List<ChatAction>? actions = widget.initialActions;
    if (actions == null) return null;
    for (final ChatAction action in actions) {
      if (action.type != 'MAP_OPEN_ROUTE_CHOOSER' &&
          action.type != 'MAP_START_ROUTE') {
        continue;
      }
      final int? flowerId = _intParam(action, 'flowerId');
      if (flowerId != null) return flowerId;
    }
    return null;
  }

  String? _initialRouteMode() {
    final List<ChatAction>? actions = widget.initialActions;
    if (actions == null) return null;
    for (final ChatAction action in actions) {
      if (action.type != 'MAP_START_ROUTE') continue;
      final Object? value = action.params?['mode'];
      final String mode = value is String ? value.trim() : '';
      if (mode.isNotEmpty) return mode;
    }
    return null;
  }

  int? _intParam(ChatAction action, String key) {
    final Object? value = action.params?[key];
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value);
    return null;
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

  List<TouristSpotData> _sortTouristSpots(
    List<TouristSpotData> spots,
    Position? position,
  ) {
    final List<TouristSpotData> copy = List<TouristSpotData>.from(spots);
    if (position == null) {
      copy.sort((TouristSpotData a, TouristSpotData b) {
        return a.title.compareTo(b.title);
      });
      return copy;
    }

    copy.sort((TouristSpotData a, TouristSpotData b) {
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

  void _captureTopBarHeight() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final BuildContext? topBarContext = _topBarKey.currentContext;
      if (topBarContext == null || !mounted) return;
      final double? height = topBarContext.size?.height;
      if (height == null || (height - _topBarHeight).abs() < 1) return;
      setState(() {
        _topBarHeight = height;
      });
      _syncRoutePanelTop();
    });
  }

  double _topOverlayStart(BuildContext context) {
    final double safeTop = MediaQuery.paddingOf(context).top;
    return safeTop + _topBarHeight + 12;
  }

  double _festivalOverlayBottom(BuildContext context) {
    if (widget.isEmbedded) return 16;
    final double screenHeight = MediaQuery.sizeOf(context).height;
    if (screenHeight < 720) return 96;
    if (screenHeight < 820) return 92;
    return 88;
  }

  @override
  void dispose() {
    if (activeState == this) {
      activeState = null;
    }
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final SeasonColors colors = SeasonTheme.getColors();
    _captureTopBarHeight();

    if (widget.isEmbedded) {
      return _buildMapBody(colors);
    }

    return Scaffold(
      backgroundColor: Colors.white,
      body: Stack(
        children: <Widget>[
          Positioned.fill(child: _buildMapBody(colors)),
          // 검색 활성 시: WebView 안에서 슬라이드 sheet 형태로 결과 패널이 뜨므로
          // Flutter 오버레이(축제 캐러셀·줌 컨트롤·챗봇 FAB)는 숨겨 겹침 방지.
          if (!_hasActiveMapSearch) _buildFestivalOverlay(colors),
          _buildTopBar(colors),
          if (!_hasActiveMapSearch) _buildZoomControls(colors),
        ],
      ),
      floatingActionButton: _hasActiveMapSearch
          ? null
          : const ChatFloatingButton(),
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
      bottomNavigationBar: const AppBottomNavigation(currentTab: AppNavTab.map),
    );
  }

  Widget _buildTopBar(SeasonColors colors) {
    final double screenWidth = MediaQuery.sizeOf(context).width;
    final bool compactPhone = screenWidth < 380;

    return Positioned(
      top: 0,
      left: 0,
      right: 0,
      child: SafeArea(
        child: Container(
          key: _topBarKey,
          margin: EdgeInsets.symmetric(
            horizontal: compactPhone ? 12 : 16,
            vertical: compactPhone ? 6 : 8,
          ),
          padding: EdgeInsets.fromLTRB(
            compactPhone ? 8 : 10,
            compactPhone ? 8 : 10,
            compactPhone ? 8 : 10,
            compactPhone ? 10 : 12,
          ),
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
                      decoration: InputDecoration(
                        hintText: '꽃 이름, 종류, 주소',
                        border: InputBorder.none,
                        isDense: true,
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 12,
                        ),
                        suffixIcon: _searchController.text.isEmpty
                            ? null
                            : IconButton(
                                icon: const Icon(Icons.close, size: 18),
                                tooltip: '검색 지우기',
                                onPressed: () {
                                  _searchController.clear();
                                  setSearchQuery('');
                                },
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
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: <Widget>[
                    _buildCategoryChip(
                      colors: colors,
                      label: '꽃',
                      accentColor: const Color(0xFFFBBF24),
                      selected: _showFlowerMarkers,
                      onTap: () async {
                        setState(
                          () => _showFlowerMarkers = !_showFlowerMarkers,
                        );
                        await _syncCategoryState();
                      },
                    ),
                    const SizedBox(width: 8),
                    _buildCategoryChip(
                      colors: colors,
                      label: '축제',
                      accentColor: const Color(0xFFFF4FA3),
                      selected: _showFestivalMarkers,
                      onTap: () async {
                        setState(
                          () => _showFestivalMarkers = !_showFestivalMarkers,
                        );
                        await _syncCategoryState();
                      },
                    ),
                    const SizedBox(width: 8),
                    _buildCategoryChip(
                      colors: colors,
                      label: '관광지',
                      accentColor: const Color(0xFF10B981),
                      selected: _showTouristMarkers,
                      onTap: () async {
                        setState(
                          () => _showTouristMarkers = !_showTouristMarkers,
                        );
                        await _syncCategoryState();
                      },
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

  Widget _buildCategoryChip({
    required SeasonColors colors,
    required String label,
    required Color accentColor,
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
              ? accentColor.withValues(alpha: 0.16)
              : Colors.grey.shade100,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color: selected
                ? accentColor.withValues(alpha: 0.46)
                : Colors.grey.shade300,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Icon(
              selected ? Icons.check_circle : Icons.circle_outlined,
              size: 16,
              color: selected ? accentColor : Colors.grey.shade500,
            ),
            const SizedBox(width: 6),
            Text(
              label,
              style: TextStyle(
                color: selected ? accentColor : Colors.grey.shade700,
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
    final double screenWidth = MediaQuery.sizeOf(context).width;
    final double screenHeight = MediaQuery.sizeOf(context).height;

    return Positioned(
      // 경로 패널과 겹치지 않도록 화면 수직 중앙 부근으로 배치
      top: screenHeight * 0.35,
      right: screenWidth < 380 ? 12 : 16,
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
    final Size screenSize = MediaQuery.sizeOf(context);
    final double screenWidth = screenSize.width;
    final double screenHeight = screenSize.height;
    final double panelWidth = switch (screenWidth) {
      < 360 => (screenWidth * 0.52).clamp(176.0, 192.0).toDouble(),
      < 420 => (screenWidth * 0.56).clamp(188.0, 216.0).toDouble(),
      < 600 => (screenWidth * 0.40).clamp(220.0, 248.0).toDouble(),
      _ => 270,
    };
    final double handleWidth = screenWidth < 380 ? 30 : 34;
    final double handleGap = screenWidth < 380 ? 6 : 8;
    final double horizontalInset = screenWidth < 380 ? 8 : 12;
    final double toggleHeight = screenHeight < 760 ? 64 : 72;

    return Positioned(
      top: _topOverlayStart(context),
      bottom: _festivalOverlayBottom(context),
      left: _isFestivalPanelOpen
          ? horizontalInset
          : horizontalInset - panelWidth + handleWidth,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
        width: panelWidth,
        child: Row(
          children: <Widget>[
            Expanded(
              child: IgnorePointer(
                ignoring:
                    !_isFestivalPanelOpen ||
                    (_isLoadingFestivals && _festivals.isEmpty),
                child: AnimatedOpacity(
                  duration: const Duration(milliseconds: 180),
                  opacity: _isFestivalPanelOpen ? 1 : 0.92,
                  child: AnimatedSwitcher(
                    duration: const Duration(milliseconds: 180),
                    child: _buildFestivalContent(colors),
                  ),
                ),
              ),
            ),
            SizedBox(width: handleGap),
            Align(
              alignment: Alignment.centerRight,
              child: GestureDetector(
                onTap: () {
                  setState(() {
                    _isFestivalPanelOpen = !_isFestivalPanelOpen;
                  });
                },
                child: Container(
                  width: screenWidth < 380 ? 24 : 26,
                  height: toggleHeight,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.96),
                    borderRadius: BorderRadius.circular(16),
                    boxShadow: <BoxShadow>[
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.10),
                        blurRadius: 10,
                        offset: const Offset(0, 3),
                      ),
                    ],
                  ),
                  child: Icon(
                    _isFestivalPanelOpen
                        ? Icons.chevron_left_rounded
                        : Icons.chevron_right_rounded,
                    color: colors.primary,
                  ),
                ),
              ),
            ),
          ],
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
          Expanded(
            child: ListView.separated(
              padding: EdgeInsets.zero,
              itemCount: visibleFestivals.length,
              separatorBuilder: (_, __) => const SizedBox(height: 12),
              itemBuilder: (BuildContext context, int index) {
                return _festivalCard(colors, visibleFestivals[index]);
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
      height: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.96),
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
    final String location = festival.fullAddress;
    final String contact = festival.tel.trim();

    return GestureDetector(
      onTap: () => _focusFestival(festival),
      child: Container(
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: Colors.black.withValues(alpha: 0.06)),
          boxShadow: <BoxShadow>[
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.04),
              blurRadius: 10,
              offset: const Offset(0, 3),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Stack(
              children: <Widget>[
                SizedBox(
                  width: double.infinity,
                  height: 136,
                  child: festival.hasImage
                      ? Image.network(
                          festival.imageUrl,
                          fit: BoxFit.cover,
                          cacheWidth: 300,
                          filterQuality: FilterQuality.medium,
                          errorBuilder: (_, __, ___) {
                            return _festivalPlaceholder(colors);
                          },
                        )
                      : _festivalPlaceholder(colors),
                ),
                Positioned(
                  top: 8,
                  right: 8,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.42),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: const Text(
                      '축제',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 10,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text(
                    festival.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: Colors.blue.shade700,
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  if (location.isNotEmpty) ...<Widget>[
                    const SizedBox(height: 8),
                    Text(
                      location,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: Colors.grey.shade800,
                        fontSize: 12,
                        height: 1.4,
                      ),
                    ),
                  ],
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
                  if (contact.isNotEmpty) ...<Widget>[
                    const SizedBox(height: 4),
                    Text(
                      contact,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: Colors.grey.shade600,
                        fontSize: 11,
                      ),
                    ),
                  ],
                  if (distance.isNotEmpty) ...<Widget>[
                    const SizedBox(height: 8),
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
          ],
        ),
      ),
    );
  }

  Widget _festivalPlaceholder(SeasonColors colors) {
    return Container(
      width: double.infinity,
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

    return _nativeWebViewWidget ?? const SizedBox.shrink();
  }
}
