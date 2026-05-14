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
    int pageNo = 1,
    int numOfRows = 60,
  }) async {
    final DateTime startDate = eventStartDate ?? _defaultEventStartDate();
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

    final List<FestivalData> broadMatches = _parseFestivalList(response.body);
    if (broadMatches.isNotEmpty) {
      return _dedupeFestivals(broadMatches);
    }

    final List<FestivalData> keywordMatches =
        await _fetchFlowerKeywordFestivals(
          pageNo: pageNo,
          numOfRows: math.min(numOfRows, 30),
        );
    return _dedupeFestivals(keywordMatches);
  }

  Future<List<FestivalData>> searchFlowerFestivals({
    String keyword = '\uAF43',
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

    return _parseFestivalList(response.body);
  }

  Future<List<FestivalData>> _fetchFlowerKeywordFestivals({
    required int pageNo,
    required int numOfRows,
  }) async {
    final List<FestivalData> merged = <FestivalData>[];

    for (final String keyword in _priorityFestivalKeywords) {
      try {
        final List<FestivalData> matches = await searchFlowerFestivals(
          keyword: keyword,
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

  List<FestivalData> _parseFestivalList(String responseBody) {
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
                festival.hasValidLocation && festival.isFlowerFestival,
          )
          .toList();
    } catch (error) {
      throw Exception('Failed to parse TourAPI response: $error');
    }
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

  static bool containsFlowerKeyword(String text) {
    final String normalized = text.toLowerCase();
    return _flowerKeywords.any(
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

  String get imageUrl => firstImage.isNotEmpty ? firstImage : firstImage2;

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
      'tel': tel,
      'mapX': mapX,
      'mapY': mapY,
    };
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
}
