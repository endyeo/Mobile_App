import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import '../api_config.dart';
import '../models/chat_action.dart';

class ChatbotResponse {
  final String reply;
  final ChatAction? action;
  final List<ChatAction> actions;
  final AgentRunTrace? agentRun;
  final List<ToolResult> toolResults;
  final String requestId;

  const ChatbotResponse({
    required this.reply,
    this.action,
    this.actions = const [],
    this.agentRun,
    this.toolResults = const [],
    this.requestId = '',
  });

  factory ChatbotResponse.fromData(Map<String, dynamic> data) {
    final actionJson = data['action'] as Map<String, dynamic>?;
    final actionsJson = data['actions'] as List<dynamic>? ?? const [];
    final agentRunJson = data['agentRun'] as Map<String, dynamic>?;
    final toolResultsJson = data['toolResults'] as List<dynamic>? ?? const [];
    final action = actionJson == null ? null : ChatAction.fromJson(actionJson);
    final actions = actionsJson
        .whereType<Map<String, dynamic>>()
        .map(ChatAction.fromJson)
        .toList();

    return ChatbotResponse(
      reply: data['reply'] as String? ?? '',
      action: action,
      actions: actions.isEmpty && action != null ? [action] : actions,
      agentRun: agentRunJson == null
          ? null
          : AgentRunTrace.fromJson(agentRunJson),
      toolResults: toolResultsJson
          .whereType<Map<String, dynamic>>()
          .map(ToolResult.fromJson)
          .toList(),
      requestId: _readRequestId(data),
    );
  }
}

class ChatbotStreamEvent {
  const ChatbotStreamEvent({
    required this.type,
    this.data = const {},
    this.stage = '',
    this.message = '',
    this.action,
    this.actions = const [],
    this.toolResult,
    this.response,
    this.requestId = '',
  });

  final String type;
  final Map<String, dynamic> data;
  final String stage;
  final String message;
  final ChatAction? action;
  final List<ChatAction> actions;
  final ToolResult? toolResult;
  final ChatbotResponse? response;
  final String requestId;

  factory ChatbotStreamEvent.fromSse(String type, Map<String, dynamic> data) {
    final normalizedType = type.replaceAll('\uFEFF', '').trim();
    final inferredType = _inferEventType(normalizedType, data);
    final actionJson = data['action'] as Map<String, dynamic>?;
    final actionsJson = data['actions'] as List<dynamic>? ?? const [];
    final toolResultJson = data['toolResult'] as Map<String, dynamic>?;
    return ChatbotStreamEvent(
      type: inferredType,
      data: data,
      stage: data['stage'] as String? ?? '',
      message: data['message'] as String? ?? '',
      action: actionJson == null ? null : ChatAction.fromJson(actionJson),
      actions: actionsJson
          .whereType<Map<String, dynamic>>()
          .map(ChatAction.fromJson)
          .toList(),
      toolResult: toolResultJson == null
          ? null
          : ToolResult.fromJson(toolResultJson),
      response: inferredType == 'FINAL_ANSWER'
          ? ChatbotResponse.fromData(data)
          : null,
      requestId: _readRequestId(data),
    );
  }

  static String _inferEventType(String type, Map<String, dynamic> data) {
    if (type != 'message' && type.isNotEmpty) return type;
    if (data.containsKey('reply')) return 'FINAL_ANSWER';
    if (data.containsKey('action') || data.containsKey('actions')) {
      return 'ACTION';
    }
    if (data.containsKey('toolResult')) return 'TOOL_RESULT';
    if (data.containsKey('reason')) return 'DONE';
    if (data.containsKey('stage') || data.containsKey('message')) {
      return 'STATUS';
    }
    return type;
  }
}

String _readRequestId(Map<String, dynamic> data) {
  final value = data['requestId'] ?? data['request_id'];
  return value == null ? '' : value.toString().trim();
}

class AgentRunTrace {
  final String mode;
  final String route;
  final String specialist;
  final List<AgentStepTrace> steps;

  const AgentRunTrace({
    required this.mode,
    required this.route,
    required this.specialist,
    required this.steps,
  });

  factory AgentRunTrace.fromJson(Map<String, dynamic> json) {
    final stepsJson = json['steps'] as List<dynamic>? ?? const [];
    return AgentRunTrace(
      mode: json['mode'] as String? ?? '',
      route: json['route'] as String? ?? '',
      specialist: json['specialist'] as String? ?? '',
      steps: stepsJson
          .whereType<Map<String, dynamic>>()
          .map(AgentStepTrace.fromJson)
          .toList(),
    );
  }
}

class AgentStepTrace {
  final int step;
  final String agent;
  final String tool;
  final String status;
  final String message;

  const AgentStepTrace({
    required this.step,
    required this.agent,
    required this.tool,
    required this.status,
    required this.message,
  });

  factory AgentStepTrace.fromJson(Map<String, dynamic> json) {
    return AgentStepTrace(
      step: json['step'] as int? ?? 0,
      agent: json['agent'] as String? ?? '',
      tool: json['tool'] as String? ?? '',
      status: json['status'] as String? ?? '',
      message: json['message'] as String? ?? '',
    );
  }
}

class ToolResult {
  final String tool;
  final String status;
  final String summary;

  const ToolResult({
    required this.tool,
    required this.status,
    required this.summary,
  });

  factory ToolResult.fromJson(Map<String, dynamic> json) {
    return ToolResult(
      tool: json['tool'] as String? ?? '',
      status: json['status'] as String? ?? '',
      summary: json['summary'] as String? ?? '',
    );
  }
}

class ChatbotService {
  static String get _baseUrl {
    if (kIsWeb) return ApiConfig.backendBaseUrl();
    if (defaultTargetPlatform == TargetPlatform.android) {
      return ApiConfig.backendBaseUrl(androidEmulator: true);
    }
    return ApiConfig.backendBaseUrl();
  }

  final Dio _client;

  ChatbotService({Dio? client})
    : _client =
          client ??
          Dio(
            BaseOptions(
              baseUrl: _baseUrl,
              connectTimeout: const Duration(seconds: 10),
              receiveTimeout: const Duration(seconds: 60),
              sendTimeout: const Duration(seconds: 10),
              headers: {'Content-Type': 'application/json'},
            ),
          );

  Future<ChatbotResponse> sendMessage({
    required String message,
    required String sessionId,
    required String requestId,
    double? lat,
    double? lng,
  }) async {
    final body = <String, dynamic>{
      'message': message,
      'session_id': sessionId,
      'request_id': requestId,
    };
    if (lat != null && lng != null) {
      body['context'] = {'lat': lat, 'lng': lng};
    }

    try {
      final response = await _client.post<Map<String, dynamic>>(
        '/chatbot/message',
        data: body,
      );
      final json = response.data ?? <String, dynamic>{};
      final data = json['data'] as Map<String, dynamic>? ?? <String, dynamic>{};
      return ChatbotResponse.fromData(data);
    } on DioException catch (error) {
      throw Exception(_messageFromDioError(error));
    }
  }

  Stream<ChatbotStreamEvent> streamMessage({
    required String message,
    required String sessionId,
    required String requestId,
    double? lat,
    double? lng,
    CancelToken? cancelToken,
  }) async* {
    final body = <String, dynamic>{
      'message': message,
      'session_id': sessionId,
      'request_id': requestId,
    };
    if (lat != null && lng != null) {
      body['context'] = {'lat': lat, 'lng': lng};
    }

    try {
      final response = await _client.post<ResponseBody>(
        '/chatbot/message/stream',
        data: body,
        cancelToken: cancelToken,
        options: Options(
          responseType: ResponseType.stream,
          headers: {'Accept': 'text/event-stream'},
        ),
      );
      final stream = response.data?.stream;
      if (stream == null) return;

      String eventName = 'message';
      final dataLines = <String>[];
      await for (final line
          in stream
              .cast<List<int>>()
              .transform(utf8.decoder)
              .transform(const LineSplitter())) {
        if (line.isEmpty) {
          final event = _parseSseEvent(eventName, dataLines);
          if (event != null) {
            _logSseEvent(event);
            yield event;
          }
          eventName = 'message';
          dataLines.clear();
          continue;
        }
        if (line.startsWith('event:')) {
          eventName = line.substring(6).trim();
        } else if (line.startsWith('data:')) {
          dataLines.add(line.substring(5).trimLeft());
        }
      }

      final event = _parseSseEvent(eventName, dataLines);
      if (event != null) {
        _logSseEvent(event);
        yield event;
      }
    } on DioException catch (error) {
      if (CancelToken.isCancel(error)) return;
      throw ChatbotStreamException.fromDio(error);
    }
  }

  Future<void> clearSession(String sessionId) async {
    await _client.delete<void>('/chatbot/session/$sessionId');
  }

  String _messageFromDioError(DioException error) {
    final data = error.response?.data;
    if (data is Map<String, dynamic>) {
      final errorJson = data['error'];
      if (errorJson is Map<String, dynamic>) {
        return errorJson['message'] as String? ?? '알 수 없는 오류가 발생했습니다.';
      }
      if (errorJson is String && errorJson.isNotEmpty) return errorJson;
    }
    return error.message ?? '알 수 없는 오류가 발생했습니다.';
  }

  ChatbotStreamEvent? _parseSseEvent(String eventName, List<String> dataLines) {
    if (dataLines.isEmpty) return null;
    final decoded = jsonDecode(dataLines.join('\n'));
    if (decoded is! Map<String, dynamic>) return null;
    return ChatbotStreamEvent.fromSse(eventName, decoded);
  }

  void _logSseEvent(ChatbotStreamEvent event) {
    if (!kDebugMode) return;
    debugPrint(
      '[SSE] ${event.type} ${event.stage} ${event.response?.reply ?? event.message}',
    );
  }
}

class ChatbotStreamException implements Exception {
  const ChatbotStreamException(this.message);

  final String message;

  factory ChatbotStreamException.fromDio(DioException error) {
    final request = error.requestOptions;
    final statusCode = error.response?.statusCode;
    final responseData = error.response?.data;
    final buffer = StringBuffer()
      ..writeln('type=${error.type}')
      ..writeln('method=${request.method}')
      ..writeln('url=${request.uri}')
      ..writeln('statusCode=${statusCode ?? 'none'}')
      ..writeln('message=${error.message ?? 'none'}');

    if (responseData != null) {
      buffer.writeln('response=$responseData');
    }

    return ChatbotStreamException(buffer.toString().trim());
  }

  @override
  String toString() => message;
}
