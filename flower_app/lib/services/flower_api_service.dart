import 'package:http/http.dart' as http;
import '../api_config.dart';

/// 공공데이터포털 "오늘의 꽃" API 서비스 (data.go.kr)
class FlowerApiService {
  static String get apiKey => ApiConfig.nongsaroKey;

  static const String _baseUrl = 'http://apis.data.go.kr/1390804/NihhsTodayFlowerInfo01';

  final http.Client _client;

  FlowerApiService({http.Client? client}) : _client = client ?? http.Client();

  /// API 키가 설정되었는지 확인
  static bool get isApiKeySet => ApiConfig.isNongsaroKeySet;

  /// 오늘의 꽃 목록 조회 (월별)
  /// [month] 1~12, [pageNo] 페이지 번호
  Future<List<FlowerData>> getFlowerList({int? month, int pageNo = 1}) async {
    final params = {
      'serviceKey': apiKey,
      'pageNo': '$pageNo',
      'numOfRows': '31',
    };
    if (month != null) {
      params['fMonth'] = '$month';
    }

    final uri = Uri.parse('$_baseUrl/selectTodayFlowerList01').replace(queryParameters: params);
    final response = await _client.get(uri).timeout(const Duration(seconds: 10));

    if (response.statusCode == 200) {
      return _parseFlowerList(response.body);
    } else {
      throw Exception('API 호출 실패: ${response.statusCode}');
    }
  }

  /// 오늘의 꽃 상세 조회
  /// [dataNo] 꽃 데이터 고유번호
  Future<FlowerDetail> getFlowerDetail(String dataNo) async {
    final params = {
      'serviceKey': apiKey,
      'dataNo': dataNo,
    };

    final uri = Uri.parse('$_baseUrl/selectTodayFlowerView01').replace(queryParameters: params);
    final response = await _client.get(uri).timeout(const Duration(seconds: 10));

    if (response.statusCode == 200) {
      return _parseFlowerDetail(response.body);
    } else {
      throw Exception('API 호출 실패: ${response.statusCode}');
    }
  }

  /// 특정 날짜의 꽃 조회
  Future<List<FlowerData>> getFlowerByDate(int month, int day) async {
    final params = {
      'serviceKey': apiKey,
      'fMonth': '$month',
      'fDay': '$day',
    };

    final uri = Uri.parse('$_baseUrl/selectTodayFlowerList01').replace(queryParameters: params);
    final response = await _client.get(uri).timeout(const Duration(seconds: 10));

    if (response.statusCode == 200) {
      return _parseFlowerList(response.body);
    } else {
      throw Exception('API 호출 실패: ${response.statusCode}');
    }
  }

  // ── XML 파싱 ──

  List<FlowerData> _parseFlowerList(String xml) {
    final List<FlowerData> flowers = [];

    // 에러 체크 (data.go.kr: resultCode '1' = 성공)
    final resultCode = _extractTag(xml, 'resultCode');
    if (resultCode != '1') {
      final msg = _extractTag(xml, 'resultMsg');
      throw Exception('API 에러: $msg (code: $resultCode)');
    }

    // <result> 태그들 추출 (data.go.kr은 <item> 대신 <result> 사용)
    final itemRegex = RegExp(r'<result>(.*?)</result>', dotAll: true);
    final matches = itemRegex.allMatches(xml);

    for (final match in matches) {
      final itemXml = match.group(1) ?? '';
      flowers.add(FlowerData(
        dataNo: _extractTag(itemXml, 'dataNo'),
        flowNm: _extractTag(itemXml, 'flowNm'),
        fMonth: int.tryParse(_extractTag(itemXml, 'fMonth')) ?? 0,
        fDay: int.tryParse(_extractTag(itemXml, 'fDay')) ?? 0,
        flowLang: _extractTag(itemXml, 'flowLang'),
        imgUrl1: _extractTag(itemXml, 'imgUrl1'),
        imgUrl2: _extractTag(itemXml, 'imgUrl2'),
        imgUrl3: _extractTag(itemXml, 'imgUrl3'),
      ));
    }

    return flowers;
  }

  FlowerDetail _parseFlowerDetail(String xml) {
    final resultCode = _extractTag(xml, 'resultCode');
    if (resultCode != '1' && resultCode != '0') {
      final msg = _extractTag(xml, 'resultMsg');
      throw Exception('API 에러: $msg (code: $resultCode)');
    }

    return FlowerDetail(
      flowNm: _extractTag(xml, 'fNm').isNotEmpty
          ? _extractTag(xml, 'fNm')
          : _extractTag(xml, 'flowNm'),
      sciNm: _extractTag(xml, 'sciNm'),
      flowLang: _extractTag(xml, 'fLang').isNotEmpty
          ? _extractTag(xml, 'fLang')
          : _extractTag(xml, 'flowLang'),
      fContent: _stripHtml(_extractTag(xml, 'fContent')),
      fUse: _stripHtml(_extractTag(xml, 'fUse')),
      fGrow: _stripHtml(_extractTag(xml, 'fGrow')),
      publishOrg: _extractTag(xml, 'publishOrg'),
      imgUrl: _extractTag(xml, 'imgUrl').isNotEmpty
          ? _extractTag(xml, 'imgUrl')
          : _extractTag(xml, 'imgUrl1'),
    );
  }

  /// XML 태그 내용 추출 헬퍼
  String _extractTag(String xml, String tag) {
    final regex = RegExp('<$tag>(.*?)</$tag>', dotAll: true);
    final match = regex.firstMatch(xml);
    if (match != null) {
      return match.group(1)?.trim() ?? '';
    }
    // CDATA 처리
    final cdataRegex = RegExp('<$tag><!\\[CDATA\\[(.*?)\\]\\]></$tag>', dotAll: true);
    final cdataMatch = cdataRegex.firstMatch(xml);
    return cdataMatch?.group(1)?.trim() ?? '';
  }

  /// HTML 태그 제거
  String _stripHtml(String html) {
    return html
        .replaceAll(RegExp(r'<[^>]*>'), '')
        .replaceAll('&nbsp;', ' ')
        .replaceAll('&amp;', '&')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .trim();
  }
}

/// 꽃 목록 데이터
class FlowerData {
  final String dataNo;
  final String flowNm;
  final int fMonth;
  final int fDay;
  final String flowLang;
  final String imgUrl1;
  final String imgUrl2;
  final String imgUrl3;

  const FlowerData({
    required this.dataNo,
    required this.flowNm,
    required this.fMonth,
    required this.fDay,
    required this.flowLang,
    this.imgUrl1 = '',
    this.imgUrl2 = '',
    this.imgUrl3 = '',
  });

  /// 대표 이미지 URL (1 > 2 > 3 순)
  String get mainImageUrl {
    if (imgUrl1.isNotEmpty) return imgUrl1;
    if (imgUrl2.isNotEmpty) return imgUrl2;
    if (imgUrl3.isNotEmpty) return imgUrl3;
    return '';
  }

  /// "M월 D일" 형식
  String get dateString => '${fMonth}월 ${fDay}일';
}

/// 꽃 상세 데이터
class FlowerDetail {
  final String flowNm;
  final String sciNm;
  final String flowLang;
  final String fContent;
  final String fUse;
  final String fGrow;
  final String publishOrg;
  final String imgUrl;

  const FlowerDetail({
    required this.flowNm,
    this.sciNm = '',
    required this.flowLang,
    this.fContent = '',
    this.fUse = '',
    this.fGrow = '',
    this.publishOrg = '',
    this.imgUrl = '',
  });
}
