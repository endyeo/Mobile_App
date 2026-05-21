import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

class LocalSavedPlace {
  const LocalSavedPlace({
    required this.sourceType,
    required this.sourceId,
    required this.name,
    required this.address,
    required this.latitude,
    required this.longitude,
    required this.imageUrl,
    required this.period,
    required this.savedAt,
  });

  final String sourceType;
  final String sourceId;
  final String name;
  final String address;
  final double latitude;
  final double longitude;
  final String imageUrl;
  final String period;
  final DateTime savedAt;

  String get key => '$sourceType:$sourceId';

  String get typeLabel {
    switch (sourceType) {
      case 'FESTIVAL':
        return '축제';
      case 'TOURIST':
        return '관광지';
      case 'FLOWER_SPOT':
        return '꽃 스팟';
      default:
        return '장소';
    }
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'sourceType': sourceType,
      'sourceId': sourceId,
      'name': name,
      'address': address,
      'latitude': latitude,
      'longitude': longitude,
      'imageUrl': imageUrl,
      'period': period,
      'savedAt': savedAt.toIso8601String(),
    };
  }

  factory LocalSavedPlace.fromJson(Map<String, dynamic> json) {
    return LocalSavedPlace(
      sourceType: json['sourceType'] as String? ?? 'PLACE',
      sourceId: json['sourceId'] as String? ?? '',
      name: json['name'] as String? ?? '장소',
      address: json['address'] as String? ?? '',
      latitude: (json['latitude'] as num?)?.toDouble() ?? 0,
      longitude: (json['longitude'] as num?)?.toDouble() ?? 0,
      imageUrl: json['imageUrl'] as String? ?? '',
      period: json['period'] as String? ?? '',
      savedAt:
          DateTime.tryParse(json['savedAt'] as String? ?? '') ?? DateTime.now(),
    );
  }

  factory LocalSavedPlace.fromMapPayload(Map<String, dynamic> payload) {
    final String type = payload['sourceType'] as String? ?? 'PLACE';
    final String id =
        payload['sourceId'] as String? ??
        payload['id'] as String? ??
        '${payload['latitude']},${payload['longitude']}';
    return LocalSavedPlace(
      sourceType: type,
      sourceId: id,
      name: payload['name'] as String? ?? '장소',
      address: payload['address'] as String? ?? '',
      latitude: (payload['latitude'] as num?)?.toDouble() ?? 0,
      longitude: (payload['longitude'] as num?)?.toDouble() ?? 0,
      imageUrl: payload['imageUrl'] as String? ?? '',
      period: payload['period'] as String? ?? '',
      savedAt: DateTime.now(),
    );
  }
}

class LocalSavedPlaceService {
  LocalSavedPlaceService._();

  static const String _storageKey = 'localSavedPlaces';

  static Future<List<LocalSavedPlace>> getPlaces() async {
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    final String raw = prefs.getString(_storageKey) ?? '[]';
    try {
      final Object? decoded = jsonDecode(raw);
      if (decoded is! List<dynamic>) return <LocalSavedPlace>[];
      return decoded
          .whereType<Map<String, dynamic>>()
          .map(LocalSavedPlace.fromJson)
          .where(
            (LocalSavedPlace place) =>
                place.name.isNotEmpty &&
                place.latitude != 0 &&
                place.longitude != 0,
          )
          .toList()
        ..sort((LocalSavedPlace a, LocalSavedPlace b) {
          return b.savedAt.compareTo(a.savedAt);
        });
    } catch (_) {
      return <LocalSavedPlace>[];
    }
  }

  static Future<bool> togglePlace(LocalSavedPlace place) async {
    final List<LocalSavedPlace> places = await getPlaces();
    final int index = places.indexWhere(
      (LocalSavedPlace entry) => entry.key == place.key,
    );
    final bool saved;
    if (index >= 0) {
      places.removeAt(index);
      saved = false;
    } else {
      places.insert(0, place);
      saved = true;
    }
    await _save(places);
    return saved;
  }

  static Future<void> removePlace(LocalSavedPlace place) async {
    final List<LocalSavedPlace> places = await getPlaces();
    places.removeWhere((LocalSavedPlace entry) => entry.key == place.key);
    await _save(places);
  }

  static Future<Set<String>> getSavedKeys() async {
    final List<LocalSavedPlace> places = await getPlaces();
    return places.map((LocalSavedPlace place) => place.key).toSet();
  }

  static Future<void> _save(List<LocalSavedPlace> places) async {
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    final String raw = jsonEncode(
      places.map((LocalSavedPlace place) => place.toJson()).toList(),
    );
    await prefs.setString(_storageKey, raw);
  }
}
