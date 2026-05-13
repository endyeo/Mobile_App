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

  const ChatbotResponse({
    required this.reply,
    this.action,
    this.actions = const [],
    this.agentRun,
    this.toolResults = const [],
  });
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
      : _client = client ??
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
    double? lat,
    double? lng,
  }) async {
    final body = <String, dynamic>{
      'message': message,
      'session_id': sessionId,
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
      final reply = data['reply'] as String? ?? '';
      final actionJson = data['action'] as Map<String, dynamic>?;
      final action = actionJson != null ? ChatAction.fromJson(actionJson) : null;
      final actionsJson = data['actions'] as List<dynamic>? ?? const [];
      final actions = actionsJson
          .whereType<Map<String, dynamic>>()
          .map(ChatAction.fromJson)
          .toList();
      final agentRunJson = data['agentRun'] as Map<String, dynamic>?;
      final toolResultsJson = data['toolResults'] as List<dynamic>? ?? const [];
      final toolResults = toolResultsJson
          .whereType<Map<String, dynamic>>()
          .map(ToolResult.fromJson)
          .toList();
      return ChatbotResponse(
        reply: reply,
        action: action,
        actions: actions.isEmpty && action != null ? [action] : actions,
        agentRun: agentRunJson != null ? AgentRunTrace.fromJson(agentRunJson) : null,
        toolResults: toolResults,
      );
    } on DioException catch (error) {
      throw Exception(_messageFromDioError(error));
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
}
