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

  factory ChatbotStreamEvent.error({
    required String message,
    String stage = 'ERROR',
    String requestId = '',
  }) {
    return ChatbotStreamEvent(
      type: 'ERROR',
      data: <String, dynamic>{
        'stage': stage,
        'message': message,
        if (requestId.isNotEmpty) 'requestId': requestId,
      },
      stage: stage,
      message: message,
      requestId: requestId,
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
    if (kIsWeb) return ApiConfig.chatbotBaseUrl();
    if (defaultTargetPlatform == TargetPlatform.android) {
      return ApiConfig.chatbotBaseUrl(androidEmulator: true);
    }
    return ApiConfig.chatbotBaseUrl();
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
          } else if (dataLines.isNotEmpty) {
            _logMalformedSseEvent(eventName, dataLines);
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
      } else if (dataLines.isNotEmpty) {
        _logMalformedSseEvent(eventName, dataLines);
      }
    } on DioException catch (error) {
      if (CancelToken.isCancel(error)) return;
      throw ChatbotStreamException.fromDio(error);
    } on FormatException catch (error) {
      _logStreamParseError(error);
      yield ChatbotStreamEvent.error(
        message: '응답 형식이 올바르지 않습니다. 다시 시도해 주세요.',
        requestId: requestId,
      );
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
    try {
      final decoded = jsonDecode(dataLines.join('\n'));
      if (decoded is! Map<String, dynamic>) return null;
      return ChatbotStreamEvent.fromSse(eventName, decoded);
    } on FormatException {
      return null;
    }
  }

  void _logSseEvent(ChatbotStreamEvent event) {
    if (!kDebugMode) return;
    debugPrint(
      '[SSE] ${event.type} ${event.stage} ${event.response?.reply ?? event.message}',
    );
  }

  void _logMalformedSseEvent(String eventName, List<String> dataLines) {
    if (!kDebugMode) return;
    debugPrint(
      '[SSE] malformed event=$eventName data=${dataLines.join('\\n')}',
    );
  }

  void _logStreamParseError(FormatException error) {
    if (!kDebugMode) return;
    debugPrint('[SSE] stream parse error: ${error.message}');
  }
}

class ChatbotStreamException implements Exception {
  const ChatbotStreamException(this.message, {this.debugMessage = ''});

  final String message;
  final String debugMessage;

  factory ChatbotStreamException.fromDio(DioException error) {
    final userMessage = _userMessageFromDio(error);
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

    return ChatbotStreamException(
      userMessage,
      debugMessage: buffer.toString().trim(),
    );
  }

  static String _userMessageFromDio(DioException error) {
    final data = error.response?.data;
    if (data is Map<String, dynamic>) {
      final message = _messageFromApiError(data);
      if (message != null) return message;
    }
    if (data is String && data.trim().isNotEmpty) {
      final decoded = _tryDecodeMap(data);
      final message = decoded == null ? null : _messageFromApiError(decoded);
      if (message != null) return message;
    }
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
        return '응답 시간이 초과되었습니다. 다시 시도해 주세요.';
      case DioExceptionType.connectionError:
        return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.';
      case DioExceptionType.badResponse:
        return '챗봇 서버 응답을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';
      case DioExceptionType.cancel:
        return '요청이 취소되었습니다.';
      case DioExceptionType.badCertificate:
      case DioExceptionType.unknown:
        return '챗봇 연결 중 문제가 발생했습니다. 다시 시도해 주세요.';
    }
  }

  static String? _messageFromApiError(Map<String, dynamic> data) {
    final errorJson = data['error'];
    if (errorJson is Map<String, dynamic>) {
      final message = errorJson['message'];
      if (message is String && message.trim().isNotEmpty) {
        return message.trim();
      }
    }
    if (errorJson is String && errorJson.trim().isNotEmpty) {
      return errorJson.trim();
    }
    final message = data['message'];
    if (message is String && message.trim().isNotEmpty) {
      return message.trim();
    }
    return null;
  }

  static Map<String, dynamic>? _tryDecodeMap(String raw) {
    try {
      final decoded = jsonDecode(raw);
      return decoded is Map<String, dynamic> ? decoded : null;
    } on FormatException {
      return null;
    }
  }

  @override
  String toString() => message;
}
