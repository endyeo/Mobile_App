import 'dart:convert';
import 'dart:math' as math;

import 'package:http/http.dart' as http;

import '../api_config.dart';

class TourApiService {
  TourApiService({http.Client? client}) : _client = client ?? http.Client();

  static String get serviceKey => ApiConfig.tourApiKey;
  static bool get isApiKeySet => ApiConfig.isTourApiKeySet;

  static const String _baseUrl = 'https://apis.data.go.kr/B551011/KorService2';
  static const List<String> _flowerKeywords = <String>[
    '\uAF43',
    '\uBC9A\uAF43',
    '\uC9C4\uB2EC\uB798',
    '\uB9E4\uD654',
    '\uC218\uAD6D',
    '\uC7A5\uBBF8',
    '\uAC1C\uB098\uB9AC',
    '\uCCA0\uCB49',
    '\uAD6D\uD654',
    '\uD574\uBC14\uB77C\uAE30',
    '\uCF54\uC2A4\uBAA8\uC2A4',
    '\uB3D9\uBC31',
    '\uC720\uCC44',
    'flower',
  ];
  static const List<String> _touristSpotKeywords = <String>[
    ..._flowerKeywords,
    '\uC218\uBAA9\uC6D0',
    '\uC2DD\uBB3C\uC6D0',
    '\uC815\uC6D0',
    '\uACF5\uC6D0',
    '\uC232',
    '\uC0DD\uD0DC\uC6D0',
    '\uC0DD\uD0DC\uACF5\uC6D0',
    '\uC790\uC5F0\uD734\uC591\uB9BC',
    '\uAF43\uAE38',
    '\uC0B0\uCC45\uB85C',
    '\uB458\uB808\uAE38',
    '\uB18D\uC6D0',
    '\uD654\uC6D0',
    'arboretum',
    'botanical',
    'garden',
    'park',
  ];
  static const List<String> _priorityFestivalKeywords = <String>[
    '\uAF43',
    '\uBC9A\uAF43',
    '\uB9E4\uD654',
    '\uC720\uCC44',
    '\uC7A5\uBBF8',
    '\uAD6D\uD654',
  ];

  final http.Client _client;

  Future<List<FestivalData>> getFlowerFestivals({
    DateTime? eventStartDate,
    DateTime? today,
    int pageNo = 1,
    int numOfRows = 60,
  }) async {
    final DateTime startDate = eventStartDate ?? _defaultEventStartDate();
    final DateTime localToday = _dateOnly(today ?? DateTime.now());
    final Map<String, String> params = <String, String>{
      'serviceKey': serviceKey,
      'numOfRows': '$numOfRows',
      'pageNo': '$pageNo',
      'MobileOS': 'ETC',
      'MobileApp': 'FlowerApp',
      '_type': 'json',
      'eventStartDate': _formatApiDate(startDate),
      'contentTypeId': '15',
    };

    final Uri uri = Uri.parse(
      '$_baseUrl/searchFestival2',
    ).replace(queryParameters: params);
    final http.Response response = await _client
        .get(uri)
        .timeout(const Duration(seconds: 15));

    if (response.statusCode != 200) {
      throw Exception('TourAPI request failed: ${response.statusCode}');
    }

    final List<FestivalData> broadMatches = _parseFestivalList(
      response.body,
      today: localToday,
    );
    if (broadMatches.isNotEmpty) {
      return _dedupeFestivals(broadMatches);
    }

    final List<FestivalData> keywordMatches =
        await _fetchFlowerKeywordFestivals(
          today: localToday,
          pageNo: pageNo,
          numOfRows: math.min(numOfRows, 30),
        );
    return _dedupeFestivals(keywordMatches);
  }

  Future<List<FestivalData>> searchFlowerFestivals({
    String keyword = '\uAF43',
    DateTime? today,
    int pageNo = 1,
    int numOfRows = 30,
  }) async {
    final Map<String, String> params = <String, String>{
      'serviceKey': serviceKey,
      'numOfRows': '$numOfRows',
      'pageNo': '$pageNo',
      'MobileOS': 'ETC',
      'MobileApp': 'FlowerApp',
      '_type': 'json',
      'keyword': keyword,
      'contentTypeId': '15',
    };

    final Uri uri = Uri.parse(
      '$_baseUrl/searchKeyword2',
    ).replace(queryParameters: params);
    final http.Response response = await _client
        .get(uri)
        .timeout(const Duration(seconds: 15));

    if (response.statusCode != 200) {
      throw Exception('TourAPI request failed: ${response.statusCode}');
    }

    return _parseFestivalList(
      response.body,
      today: _dateOnly(today ?? DateTime.now()),
    );
  }

  Future<List<TouristSpotData>> getFlowerTouristSpots({
    required double latitude,
    required double longitude,
    int radius = 5000,
    int pageNo = 1,
    int numOfRows = 80,
  }) async {
    final Map<String, String> params = <String, String>{
      'serviceKey': serviceKey,
      'numOfRows': '$numOfRows',
      'pageNo': '$pageNo',
      'MobileOS': 'ETC',
      'MobileApp': 'FlowerApp',
      '_type': 'json',
      'mapX': '$longitude',
      'mapY': '$latitude',
      'radius': '$radius',
      'arrange': 'E',
      'contentTypeId': '12',
    };

    final Uri uri = Uri.parse(
      '$_baseUrl/locationBasedList2',
    ).replace(queryParameters: params);
    final http.Response response = await _client
        .get(uri)
        .timeout(const Duration(seconds: 15));

    if (response.statusCode != 200) {
      throw Exception('TourAPI request failed: ${response.statusCode}');
    }

    return _dedupeTouristSpots(_parseTouristSpotList(response.body));
  }

  Future<List<TouristSpotData>> getNationwideFlowerTouristSpots({
    int maxResults = 300,
    int rowsPerKeyword = 35,
  }) async {
    final List<TouristSpotData> merged = <TouristSpotData>[];
    final List<String> keywords = _touristSpotKeywords
        .where((String keyword) => !RegExp(r'^[a-zA-Z]+$').hasMatch(keyword))
        .take(12)
        .toList();

    for (final String keyword in keywords) {
      if (merged.length >= maxResults) break;
      try {
        final Map<String, String> params = <String, String>{
          'serviceKey': serviceKey,
          'numOfRows': '$rowsPerKeyword',
          'pageNo': '1',
          'MobileOS': 'ETC',
          'MobileApp': 'FlowerApp',
          '_type': 'json',
          'keyword': keyword,
          'arrange': 'A',
          'contentTypeId': '12',
        };
        final Uri uri = Uri.parse(
          '$_baseUrl/searchKeyword2',
        ).replace(queryParameters: params);
        final http.Response response = await _client
            .get(uri)
            .timeout(const Duration(seconds: 15));
        if (response.statusCode != 200) continue;
        merged.addAll(_parseTouristSpotList(response.body));
      } catch (_) {
        // Keep partial nationwide results from other keywords.
      }
    }

    final List<TouristSpotData> results = _dedupeTouristSpots(merged);
    return results.length > maxResults
        ? results.take(maxResults).toList()
        : results;
  }

  Future<List<FestivalData>> _fetchFlowerKeywordFestivals({
    required DateTime today,
    required int pageNo,
    required int numOfRows,
  }) async {
    final List<FestivalData> merged = <FestivalData>[];

    for (final String keyword in _priorityFestivalKeywords) {
      try {
        final List<FestivalData> matches = await searchFlowerFestivals(
          keyword: keyword,
          today: today,
          pageNo: pageNo,
          numOfRows: numOfRows,
        );
        merged.addAll(matches);
      } catch (_) {
        // Keep partial results from other keywords.
      }
    }

    return merged;
  }

  List<FestivalData> _dedupeFestivals(List<FestivalData> festivals) {
    final Map<String, FestivalData> deduped = <String, FestivalData>{};

    for (final FestivalData festival in festivals) {
      final String key = festival.contentId.isNotEmpty
          ? festival.contentId
          : '${festival.title}_${festival.mapX}_${festival.mapY}';
      deduped.putIfAbsent(key, () => festival);
    }

    final List<FestivalData> results = deduped.values.toList();
    results.sort((FestivalData a, FestivalData b) {
      final String aStart = a.eventStartDate;
      final String bStart = b.eventStartDate;
      if (aStart.isEmpty && bStart.isEmpty) return a.title.compareTo(b.title);
      if (aStart.isEmpty) return 1;
      if (bStart.isEmpty) return -1;
      return aStart.compareTo(bStart);
    });
    return results;
  }

  List<FestivalData> _parseFestivalList(
    String responseBody, {
    required DateTime today,
  }) {
    try {
      final Map<String, dynamic> decoded =
          jsonDecode(responseBody) as Map<String, dynamic>;
      final dynamic items = decoded['response']?['body']?['items']?['item'];
      final List<dynamic> itemList = items is List<dynamic>
          ? items
          : items == null
          ? const <dynamic>[]
          : <dynamic>[items];

      return itemList
          .whereType<Map<String, dynamic>>()
          .map(FestivalData.fromApi)
          .where(
            (FestivalData festival) =>
                festival.hasValidLocation &&
                festival.isFlowerFestival &&
                !festival.isPast(today),
          )
          .toList();
    } catch (error) {
      throw Exception('Failed to parse TourAPI response: $error');
    }
  }

  List<TouristSpotData> _parseTouristSpotList(String responseBody) {
    try {
      final Map<String, dynamic> decoded =
          jsonDecode(responseBody) as Map<String, dynamic>;
      final dynamic items = decoded['response']?['body']?['items']?['item'];
      final List<dynamic> itemList = items is List<dynamic>
          ? items
          : items == null
          ? const <dynamic>[]
          : <dynamic>[items];

      return itemList
          .whereType<Map<String, dynamic>>()
          .map(TouristSpotData.fromApi)
          .where(
            (TouristSpotData spot) =>
                spot.hasValidLocation && spot.containsTouristSpotKeyword,
          )
          .toList();
    } catch (error) {
      throw Exception('Failed to parse TourAPI tourist response: $error');
    }
  }

  List<TouristSpotData> _dedupeTouristSpots(List<TouristSpotData> spots) {
    final Map<String, TouristSpotData> deduped = <String, TouristSpotData>{};

    for (final TouristSpotData spot in spots) {
      final String key = spot.contentId.isNotEmpty
          ? spot.contentId
          : '${spot.title}_${spot.mapX}_${spot.mapY}';
      deduped.putIfAbsent(key, () => spot);
    }

    return deduped.values.toList();
  }

  DateTime _defaultEventStartDate() {
    final DateTime now = DateTime.now();
    return DateTime(now.year, now.month - 3, now.day);
  }

  String _formatApiDate(DateTime date) {
    return '${date.year}'
        '${date.month.toString().padLeft(2, '0')}'
        '${date.day.toString().padLeft(2, '0')}';
  }

  DateTime _dateOnly(DateTime value) {
    return DateTime(value.year, value.month, value.day);
  }

  static bool containsFlowerKeyword(String text) {
    return _containsAnyKeyword(text, _flowerKeywords);
  }

  static bool containsTouristSpotKeyword(String text) {
    return _containsAnyKeyword(text, _touristSpotKeywords);
  }

  static bool _containsAnyKeyword(String text, List<String> keywords) {
    final String normalized = text.toLowerCase();
    return keywords.any(
      (String keyword) => normalized.contains(keyword.toLowerCase()),
    );
  }
}

class FestivalData {
  const FestivalData({
    required this.contentId,
    required this.title,
    required this.addr1,
    required this.addr2,
    required this.mapX,
    required this.mapY,
    required this.firstImage,
    required this.firstImage2,
    required this.tel,
    required this.eventStartDate,
    required this.eventEndDate,
  });

  factory FestivalData.fromApi(Map<String, dynamic> json) {
    return FestivalData(
      contentId: (json['contentid'] ?? '').toString(),
      title: (json['title'] ?? '').toString(),
      addr1: (json['addr1'] ?? '').toString(),
      addr2: (json['addr2'] ?? '').toString(),
      mapX: double.tryParse((json['mapx'] ?? '').toString()) ?? 0,
      mapY: double.tryParse((json['mapy'] ?? '').toString()) ?? 0,
      firstImage: (json['firstimage'] ?? '').toString(),
      firstImage2: (json['firstimage2'] ?? '').toString(),
      tel: (json['tel'] ?? '').toString(),
      eventStartDate: (json['eventstartdate'] ?? '').toString(),
      eventEndDate: (json['eventenddate'] ?? '').toString(),
    );
  }

  final String contentId;
  final String title;
  final String addr1;
  final String addr2;
  final double mapX;
  final double mapY;
  final String firstImage;
  final String firstImage2;
  final String tel;
  final String eventStartDate;
  final String eventEndDate;

  bool get hasValidLocation => mapX != 0 && mapY != 0;
  bool get hasImage => imageUrl.isNotEmpty;
  bool get isFlowerFestival => TourApiService.containsFlowerKeyword(title);

  String get fullAddress =>
      <String>[addr1, addr2].where((String e) => e.isNotEmpty).join(' ');

  String get imageUrl =>
      _normalizeImageUrl(firstImage2.isNotEmpty ? firstImage2 : firstImage);

  String get periodString {
    if (eventStartDate.isEmpty) return '';
    final String start = _formatDate(eventStartDate);
    final String end = _formatDate(eventEndDate);
    return end.isNotEmpty ? '$start - $end' : start;
  }

  double distanceFrom({required double latitude, required double longitude}) {
    return _distanceMeters(latitude, longitude, mapY, mapX);
  }

  Map<String, dynamic> toMapPayload() {
    return <String, dynamic>{
      'contentId': contentId,
      'title': title,
      'address': fullAddress,
      'imageUrl': imageUrl,
      'period': periodString,
      'eventStartDate': eventStartDate,
      'eventEndDate': eventEndDate,
      'tel': tel,
      'mapX': mapX,
      'mapY': mapY,
      'contentTypeId': '15',
      'type': 'festival',
    };
  }

  bool isPast(DateTime today) {
    final DateTime? endDate = _parseApiDate(eventEndDate);
    if (endDate == null) return false;
    return endDate.isBefore(DateTime(today.year, today.month, today.day));
  }

  String _formatDate(String value) {
    if (value.length != 8) return value;
    return '${value.substring(0, 4)}.'
        '${value.substring(4, 6)}.'
        '${value.substring(6, 8)}';
  }

  static double _distanceMeters(
    double lat1,
    double lng1,
    double lat2,
    double lng2,
  ) {
    const double earthRadius = 6371000;
    final double dLat = _toRadians(lat2 - lat1);
    final double dLng = _toRadians(lng2 - lng1);
    final double a =
        _sinSquared(dLat / 2) +
        math.cos(_toRadians(lat1)) *
            math.cos(_toRadians(lat2)) *
            _sinSquared(dLng / 2);
    return earthRadius * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a));
  }

  static double _toRadians(double value) => value * math.pi / 180;

  static double _sinSquared(double value) {
    final double s = math.sin(value);
    return s * s;
  }

  static String _normalizeImageUrl(String value) {
    final String trimmed = value.trim();
    if (trimmed.startsWith('http://')) {
      return 'https://${trimmed.substring(7)}';
    }
    return trimmed;
  }

  static DateTime? _parseApiDate(String value) {
    if (value.length != 8) return null;
    final int? year = int.tryParse(value.substring(0, 4));
    final int? month = int.tryParse(value.substring(4, 6));
    final int? day = int.tryParse(value.substring(6, 8));
    if (year == null || month == null || day == null) return null;
    return DateTime(year, month, day);
  }
}

class TouristSpotData {
  const TouristSpotData({
    required this.contentId,
    required this.title,
    required this.addr1,
    required this.addr2,
    required this.mapX,
    required this.mapY,
    required this.firstImage,
    required this.firstImage2,
    required this.contentTypeId,
  });

  factory TouristSpotData.fromApi(Map<String, dynamic> json) {
    return TouristSpotData(
      contentId: (json['contentid'] ?? '').toString(),
      title: (json['title'] ?? '').toString(),
      addr1: (json['addr1'] ?? '').toString(),
      addr2: (json['addr2'] ?? '').toString(),
      mapX: double.tryParse((json['mapx'] ?? '').toString()) ?? 0,
      mapY: double.tryParse((json['mapy'] ?? '').toString()) ?? 0,
      firstImage: (json['firstimage'] ?? '').toString(),
      firstImage2: (json['firstimage2'] ?? '').toString(),
      contentTypeId: (json['contenttypeid'] ?? '12').toString(),
    );
  }

  final String contentId;
  final String title;
  final String addr1;
  final String addr2;
  final double mapX;
  final double mapY;
  final String firstImage;
  final String firstImage2;
  final String contentTypeId;

  bool get hasValidLocation => mapX != 0 && mapY != 0;
  bool get containsTouristSpotKeyword =>
      TourApiService.containsTouristSpotKeyword('$title $fullAddress');

  String get fullAddress =>
      <String>[addr1, addr2].where((String e) => e.isNotEmpty).join(' ');

  String get imageUrl => FestivalData._normalizeImageUrl(
    firstImage2.isNotEmpty ? firstImage2 : firstImage,
  );

  double distanceFrom({required double latitude, required double longitude}) {
    return FestivalData._distanceMeters(latitude, longitude, mapY, mapX);
  }

  Map<String, dynamic> toMapPayload() {
    return <String, dynamic>{
      'contentId': contentId,
      'title': title,
      'address': fullAddress,
      'imageUrl': imageUrl,
      'mapX': mapX,
      'mapY': mapY,
      'contentTypeId': contentTypeId,
      'type': 'tourist',
    };
  }
}
