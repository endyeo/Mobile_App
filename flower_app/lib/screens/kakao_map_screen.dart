import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../api_config.dart';
import '../models/chat_action.dart';
import '../theme/season_theme.dart';
import '../widgets/app_bottom_navigation.dart';
import '../widgets/chat_floating_button.dart';
import '../widgets/map_html_view.dart';

class KakaoMapScreen extends StatefulWidget {
  final bool isEmbedded;
  final List<ChatAction>? initialActions;

  const KakaoMapScreen({
    super.key,
    this.isEmbedded = false,
    this.initialActions,
  });

  @override
  State<KakaoMapScreen> createState() => KakaoMapScreenState();
}

class KakaoMapScreenState extends State<KakaoMapScreen> {
  WebViewController? _controller;
  String? _mapHtml;
  final TextEditingController _searchController = TextEditingController();
  late final String _webMapViewType = 'flower-map-${identityHashCode(this)}';
  var _isLoading = true;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    if (!kIsWeb) {
      _configureWebViewController();
    }
    _loadMapHtml();
  }

  void _configureWebViewController() {
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.transparent)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageFinished: (_) {
            if (mounted) {
              setState(() => _isLoading = false);
              _applyInitialActions();
            }
          },
          onWebResourceError: (error) {
            if (mounted && error.isForMainFrame == true) {
              setState(() {
                _isLoading = false;
                _errorMessage = error.description;
              });
            }
          },
          onNavigationRequest: (request) {
            final uri = Uri.tryParse(request.url);
            if (uri != null && _shouldOpenExternally(uri)) {
              _openExternal(uri);
              return NavigationDecision.prevent;
            }
            return NavigationDecision.navigate;
          },
        ),
      );
  }

  Future<void> _applyInitialActions() async {
    final actions = widget.initialActions;
    if (actions == null || actions.isEmpty) return;
    for (final action in actions) {
      if (action.type == 'MAP_SET_SEARCH_QUERY') {
        final query = action.params?['query'] as String? ?? action.params?['q'] as String? ?? '';
        if (query.isNotEmpty) await setSearchQuery(query);
      }
    }
  }

  Future<void> setSearchQuery(String query) async {
    final escaped = query.replaceAll("'", "\\'");
    await _controller?.runJavaScript(
      "var el = document.getElementById('search-input');"
      "if(el){ el.value='$escaped'; el.dispatchEvent(new Event('input',{bubbles:true})); }",
    );
  }

  Future<void> zoomIn() async {
    await _controller?.runJavaScript("document.getElementById('btn-zoom-in')?.click();");
  }

  Future<void> zoomOut() async {
    await _controller?.runJavaScript("document.getElementById('btn-zoom-out')?.click();");
  }

  Future<void> moveToCurrentLocation() async {
    await _controller?.runJavaScript("document.getElementById('btn-gps')?.click();");
  }

  Future<void> _loadMapHtml() async {
    try {
      if (kIsWeb) {
        setState(() {
          _mapHtml = _buildWebMapAssetUrl();
          _isLoading = false;
        });
        return;
      }

      final useAndroidHost = defaultTargetPlatform == TargetPlatform.android;
      final mapConfig = Uri(queryParameters: {
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
      }).query;
      await _controller!.loadRequest(
        Uri.parse('https://ourt.kro.kr/map/index.html#$mapConfig'),
      );
    } catch (error) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorMessage = error.toString();
        });
      }
    }
  }

  String _buildWebMapAssetUrl() {
    final mapConfig = {
      'kakaoKey': ApiConfig.kakaoMapKey,
      'apiBaseUrl': ApiConfig.mapApiBaseUrl(androidEmulator: false),
      'centerLat': '37.5665',
      'centerLng': '126.9780',
      'zoom': '5',
      'radius': '5000',
      'maxRadius': '50000',
      'limit': '50',
      'embedded': widget.isEmbedded ? '1' : '0',
      'tourApiKey': ApiConfig.tourApiKey,
    };
    return 'assets/assets/map/index.html#${Uri(queryParameters: mapConfig).query}';
  }

  String _buildMapHtml({
    required String style,
    required String app,
  }) {
    final useAndroidHost = !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
    final mapConfig = {
      'KAKAO_APP_KEY': ApiConfig.kakaoMapKey,
      'API_BASE_URL': ApiConfig.mapApiBaseUrl(androidEmulator: useAndroidHost),
      'DEFAULT_CENTER': {'lat': 37.5665, 'lng': 126.9780},
      'DEFAULT_ZOOM_LEVEL': 5,
      'DEFAULT_RADIUS': 5000,
      'MAX_RADIUS': 50000,
      'DEFAULT_LIMIT': 50,
      'TOUR_API_KEY': ApiConfig.tourApiKey,
    };

    final kakaoScript = ApiConfig.isKakaoKeySet
        ? '''
<script>
  (function () {
    var script = document.createElement('script');
    script.src = 'https://dapi.kakao.com/v2/maps/sdk.js?appkey=${Uri.encodeComponent(ApiConfig.kakaoMapKey)}&libraries=services&autoload=false';
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
'''
        : '';

    return '''
<!doctype html>
<html lang="en" class="${widget.isEmbedded ? 'embedded-map' : ''}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>FLOWER Map</title>
  <style>$style</style>
  <script>window.MAP_CONFIG = ${jsonEncode(mapConfig)};</script>
  $kakaoScript
</head>
<body>
  <main id="map-shell">
    <section id="map"></section>
    <header class="top-bar">
      <strong>FLOWER</strong>
      <label class="search-box">
        <span>Search</span>
        <input id="search-input" type="search" placeholder="Flower name, species, address">
      </label>
    </header>
    <section class="filters">
      <div id="species-filter" class="chip-row"></div>
      <div id="status-filter" class="chip-row"></div>
    </section>
    <section class="radius-card">
      <div>
        <strong>Search radius</strong>
        <span id="radius-value">5.0 km</span>
      </div>
      <input id="radius-slider" type="range" min="500" max="50000" step="500" value="5000">
    </section>
    <nav class="map-controls" aria-label="Map controls">
      <button id="btn-zoom-in" type="button" aria-label="Zoom in">+</button>
      <button id="btn-zoom-out" type="button" aria-label="Zoom out">-</button>
      <button id="btn-gps" type="button" aria-label="Move to current location">GPS</button>
    </nav>
    <aside id="info-badge" class="info-badge"><span class="count">0</span> flowers nearby</aside>
    <aside id="no-results" class="no-results" hidden>No flowers match these filters.</aside>
  </main>
  <div id="bottom-sheet-overlay" class="bottom-sheet-overlay"></div>
  <section id="bottom-sheet" class="bottom-sheet" aria-label="Flower detail">
    <div class="bottom-sheet-handle"></div>
    <div id="flower-detail-content" class="bottom-sheet-content"></div>
  </section>
  <script>$app</script>
</body>
</html>
''';
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

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();

    if (widget.isEmbedded) {
      return _buildMapBody(colors);
    }

    return Scaffold(
      backgroundColor: Colors.white,
      body: Stack(
        children: [
          Positioned.fill(child: _buildMapBody(colors)),
          _buildTopBar(colors),
          _buildZoomControls(colors),
        ],
      ),
      floatingActionButton: const ChatFloatingButton(),
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
      bottomNavigationBar: const AppBottomNavigation(currentTab: AppNavTab.map),
    );
  }

  Widget _buildTopBar(SeasonColors colors) {
    return Positioned(
      top: 0, left: 0, right: 0,
      child: SafeArea(
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.88),
            borderRadius: BorderRadius.circular(20),
            boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.08), blurRadius: 12, offset: const Offset(0, 3))],
          ),
          child: Row(
            children: [
              IconButton(
                icon: Icon(Icons.arrow_back_ios_new, color: colors.primary, size: 18),
                tooltip: '뒤로가기',
                onPressed: () => Navigator.pop(context),
              ),
              Expanded(
                child: TextField(
                  controller: _searchController,
                  textInputAction: TextInputAction.search,
                  decoration: const InputDecoration(
                    hintText: '꽃 이름, 종류, 주소',
                    border: InputBorder.none,
                    isDense: true,
                    contentPadding: EdgeInsets.symmetric(horizontal: 8, vertical: 12),
                  ),
                  onSubmitted: (value) => setSearchQuery(value.trim()),
                ),
              ),
              IconButton(
                icon: Icon(Icons.search, color: colors.primary),
                tooltip: '검색',
                onPressed: () => setSearchQuery(_searchController.text.trim()),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildZoomControls(SeasonColors colors) {
    return Positioned(
      top: 100, right: 16,
      child: SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _mapControlButton(colors: colors, icon: Icons.add, tooltip: '확대', onTap: zoomIn),
            const SizedBox(height: 8),
            _mapControlButton(colors: colors, icon: Icons.remove, tooltip: '축소', onTap: zoomOut),
            const SizedBox(height: 8),
            _mapControlButton(colors: colors, icon: Icons.my_location, tooltip: '현재 위치', onTap: moveToCurrentLocation),
          ],
        ),
      ),
    );
  }

  Widget _mapControlButton({required SeasonColors colors, required IconData icon, required String tooltip, required VoidCallback onTap}) {
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: 44, height: 44,
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.92),
            shape: BoxShape.circle,
            boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.12), blurRadius: 8)],
          ),
          child: Icon(icon, color: colors.primary, size: 22),
        ),
      ),
    );
  }

  Widget _buildMapBody(SeasonColors colors) {
    return Stack(
      children: [
        Positioned.fill(child: _buildMapContent()),
        if (_isLoading)
          Positioned.fill(
            child: ColoredBox(
              color: Colors.white.withAlpha(220),
              child: Center(child: CircularProgressIndicator(color: colors.primary)),
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
                    children: [
                      Icon(Icons.map_outlined, size: 44, color: colors.primary),
                      const SizedBox(height: 12),
                      const Text('지도를 불러오지 못했습니다.', style: TextStyle(fontWeight: FontWeight.bold)),
                      const SizedBox(height: 8),
                      Text(_errorMessage!, textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.grey[600], fontSize: 12)),
                      const SizedBox(height: 16),
                      ElevatedButton(
                        onPressed: () {
                          setState(() { _errorMessage = null; _isLoading = true; });
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
      final html = _mapHtml;
      if (html == null) return const SizedBox.shrink();
      return buildMapHtmlView(
        viewType: _webMapViewType,
        html: html,
        isInteractive: !widget.isEmbedded,
      );
    }

    final controller = _controller;
    if (controller == null) return const SizedBox.shrink();
    return WebViewWidget(controller: controller);
  }
}
