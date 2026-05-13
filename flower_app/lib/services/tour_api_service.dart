import 'package:http/http.dart' as http;
import '../api_config.dart';

/// 한국관광공사 TourAPI 서비스
class TourApiService {
  static String get serviceKey => ApiConfig.tourApiKey;

  static const String _baseUrl =
      'https://apis.data.go.kr/B551011/KorService2';

  final http.Client _client;

  TourApiService({http.Client? client}) : _client = client ?? http.Client();

  /// API 키가 설정되었는지 확인
  static bool get isApiKeySet => ApiConfig.isTourApiKeySet;

  /// 꽃 관련 축제/행사 검색
  /// contentTypeId=15 (축제/공연/행사)
  Future<List<FestivalData>> searchFlowerFestivals({
    String keyword = '꽃',
    int pageNo = 1,
    int numOfRows = 30,
  }) async {
    final params = {
      'serviceKey': serviceKey,
      'numOfRows': '$numOfRows',
      'pageNo': '$pageNo',
      'MobileOS': 'ETC',
      'MobileApp': 'FlowerApp',
      '_type': 'json',
      'keyword': keyword,
      'contentTypeId': '15', // 축제/공연/행사
    };

    final uri = Uri.parse('$_baseUrl/searchKeyword2')
        .replace(queryParameters: params);
    final response =
        await _client.get(uri).timeout(const Duration(seconds: 15));

    if (response.statusCode == 200) {
      return _parseFestivalList(response.body);
    } else {
      throw Exception('TourAPI 호출 실패: ${response.statusCode}');
    }
  }

  /// 현재 진행 중인 축제 조회 (날짜 기반)
  Future<List<FestivalData>> getOngoingFestivals({
    int pageNo = 1,
    int numOfRows = 30,
  }) async {
    final now = DateTime.now();
    final eventStartDate =
        '${now.year}${now.month.toString().padLeft(2, '0')}${now.day.toString().padLeft(2, '0')}';

    final params = {
      'serviceKey': serviceKey,
      'numOfRows': '$numOfRows',
      'pageNo': '$pageNo',
      'MobileOS': 'ETC',
      'MobileApp': 'FlowerApp',
      '_type': 'json',
      'eventStartDate': eventStartDate,
      'contentTypeId': '15',
    };

    final uri = Uri.parse('$_baseUrl/searchFestival2')
        .replace(queryParameters: params);
    final response =
        await _client.get(uri).timeout(const Duration(seconds: 15));

    if (response.statusCode == 200) {
      return _parseFestivalList(response.body);
    } else {
      throw Exception('TourAPI 호출 실패: ${response.statusCode}');
    }
  }

  /// JSON 파싱
  List<FestivalData> _parseFestivalList(String jsonStr) {
    try {
      // 간단 JSON 파싱 (dart:convert 없이 RegExp)
      final festivals = <FestivalData>[];

      // items 배열에서 각 item 추출
      final itemRegex = RegExp(r'\{[^{}]*"contentid"[^{}]*\}', dotAll: true);
      final matches = itemRegex.allMatches(jsonStr);

      for (final match in matches) {
        final item = match.group(0) ?? '';
        festivals.add(FestivalData(
          contentId: _jsonValue(item, 'contentid'),
          title: _jsonValue(item, 'title'),
          addr1: _jsonValue(item, 'addr1'),
          addr2: _jsonValue(item, 'addr2'),
          mapX: double.tryParse(_jsonValue(item, 'mapx')) ?? 0,
          mapY: double.tryParse(_jsonValue(item, 'mapy')) ?? 0,
          firstImage: _jsonValue(item, 'firstimage'),
          firstImage2: _jsonValue(item, 'firstimage2'),
          tel: _jsonValue(item, 'tel'),
          eventStartDate: _jsonValue(item, 'eventstartdate'),
          eventEndDate: _jsonValue(item, 'eventenddate'),
        ));
      }

      // 좌표가 있는 것만 필터 (지도에 표시 가능)
      return festivals.where((f) => f.mapX != 0 && f.mapY != 0).toList();
    } catch (e) {
      throw Exception('데이터 파싱 실패: $e');
    }
  }

  /// JSON 값 추출 헬퍼
  String _jsonValue(String json, String key) {
    final regex = RegExp('"$key"\\s*:\\s*"([^"]*)"');
    final match = regex.firstMatch(json);
    return match?.group(1) ?? '';
  }
}

/// 축제 데이터 모델
class FestivalData {
  final String contentId;
  final String title;
  final String addr1;
  final String addr2;
  final double mapX; // 경도 (longitude)
  final double mapY; // 위도 (latitude)
  final String firstImage;
  final String firstImage2;
  final String tel;
  final String eventStartDate;
  final String eventEndDate;

  const FestivalData({
    required this.contentId,
    required this.title,
    this.addr1 = '',
    this.addr2 = '',
    this.mapX = 0,
    this.mapY = 0,
    this.firstImage = '',
    this.firstImage2 = '',
    this.tel = '',
    this.eventStartDate = '',
    this.eventEndDate = '',
  });

  String get fullAddress => '$addr1 $addr2'.trim();
  String get imageUrl =>
      firstImage.isNotEmpty ? firstImage : firstImage2;

  /// 축제 기간 표시 문자열
  String get periodString {
    if (eventStartDate.isEmpty) return '';
    final start = _formatDate(eventStartDate);
    final end = _formatDate(eventEndDate);
    return '$start ~ $end';
  }

  String _formatDate(String yyyymmdd) {
    if (yyyymmdd.length != 8) return yyyymmdd;
    return '${yyyymmdd.substring(0, 4)}.${yyyymmdd.substring(4, 6)}.${yyyymmdd.substring(6, 8)}';
  }

  /// 꽃 축제인지 판별 (제목에 꽃 관련 키워드)
  bool get isFlowerFestival {
    const keywords = [
      '꽃', '벚꽃', '장미', '튤립', '해바라기', '유채', '철쭉',
      '진달래', '국화', '수국', '매화', '개나리', '라벤더', '코스모스',
      '동백', 'flower', '플라워', '봄꽃', '가을꽃',
    ];
    final t = title.toLowerCase();
    return keywords.any((k) => t.contains(k));
  }
}
