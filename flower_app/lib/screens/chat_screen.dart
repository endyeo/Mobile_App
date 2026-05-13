import 'package:flutter/material.dart';
import 'package:uuid/uuid.dart';

import '../app_actions/app_action_runtime.dart';
import '../models/chat_action.dart';
import '../models/chat_message.dart';
import '../services/chatbot_service.dart';
import '../theme/season_theme.dart';
import '../widgets/message_bubble.dart';

class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key});

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  static final List<ChatMessage> _sharedMessages = [];
  static String? _sharedSessionId;
  static AgentRunTrace? _sharedAgentRun;
  static List<ToolResult> _sharedToolResults = const [];

  final List<ChatMessage> _messages = _sharedMessages;
  final TextEditingController _textController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final ChatbotService _chatbotService = ChatbotService();

  late String _sessionId;
  bool _isLoading = false;
  AgentRunTrace? _currentAgentRun;
  List<ToolResult> _currentToolResults = const [];

  @override
  void initState() {
    super.initState();
    _initSession();
    _currentAgentRun = _sharedAgentRun;
    _currentToolResults = _sharedToolResults;
    if (_messages.isEmpty) {
      _addBotMessage('안녕하세요. 궁금한 꽃, 지도 이동, 커뮤니티 작업을 말해 주세요.');
    }
  }

  @override
  void dispose() {
    _textController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _initSession() {
    _sessionId = _sharedSessionId ?? const Uuid().v4();
    _sharedSessionId = _sessionId;
  }

  Future<void> _resetSession() async {
    try {
      await _chatbotService.clearSession(_sessionId);
    } catch (e) {
      debugPrint('세션 초기화 실패: $e');
    }
    setState(() {
      _messages.clear();
      _currentAgentRun = null;
      _currentToolResults = const [];
      _sharedAgentRun = null;
      _sharedToolResults = const [];
      _sharedSessionId = null;
      _initSession();
      _messages.insert(0, ChatMessage.bot('대화가 초기화되었습니다. 새로 질문해 주세요.'));
    });
  }

  void _addBotMessage(String text, {ChatAction? action}) {
    setState(() {
      _messages.insert(0, ChatMessage.bot(text, action: action));
    });
    _scrollToBottom();
  }

  Future<void> _sendMessage() async {
    final text = _textController.text.trim();
    if (text.isEmpty || _isLoading) return;

    _textController.clear();
    FocusScope.of(context).unfocus();

    setState(() {
      _messages.insert(0, ChatMessage.user(text));
      _messages.insert(0, ChatMessage.loading());
      _isLoading = true;
      _currentAgentRun = null;
      _currentToolResults = const [];
      _sharedAgentRun = null;
      _sharedToolResults = const [];
    });
    _scrollToBottom();

    try {
      final response = await _chatbotService.sendMessage(
        message: text,
        sessionId: _sessionId,
        lat: 37.5665,
        lng: 126.9780,
      );

      setState(() {
        _messages.removeAt(0);
        _messages.insert(0, ChatMessage.bot(response.reply, action: response.action));
        _isLoading = false;
        _currentAgentRun = response.agentRun;
        _currentToolResults = response.toolResults;
        _sharedAgentRun = response.agentRun;
        _sharedToolResults = response.toolResults;
      });
      _scrollToBottom();

      if (mounted) {
        await AppActionRuntime.execute(context, response.actions);
      }
    } catch (e) {
      setState(() {
        _messages.removeAt(0);
        _isLoading = false;
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('오류가 발생했습니다: $e'),
            backgroundColor: Colors.redAccent,
          ),
        );
      }
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          0.0,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();

    return Scaffold(
      backgroundColor: colors.background,
      body: Stack(
        children: [
          Positioned.fill(child: _buildBackground(colors)),
          SafeArea(
            child: Column(
              children: [
                const SizedBox(height: 76),
                if (_currentAgentRun != null)
                  _buildAgentActivityPanel(_currentAgentRun!, _currentToolResults, colors),
                Expanded(
                  child: ListView.builder(
                    controller: _scrollController,
                    reverse: true,
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 112),
                    itemCount: _messages.length,
                    itemBuilder: (context, index) {
                      return MessageBubble(message: _messages[index]);
                    },
                  ),
                ),
              ],
            ),
          ),
          _buildTopBar(colors),
          _buildInputArea(colors),
        ],
      ),
    );
  }

  Widget _buildBackground(SeasonColors colors) {
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [colors.background, Colors.white],
        ),
      ),
    );
  }

  Widget _buildTopBar(SeasonColors colors) {
    return Positioned(
      top: 0,
      left: 0,
      right: 0,
      child: SafeArea(
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
          decoration: BoxDecoration(
            color: Colors.white.withAlpha(224),
            borderRadius: BorderRadius.circular(20),
            boxShadow: [
              BoxShadow(color: Colors.black.withAlpha(20), blurRadius: 12, offset: const Offset(0, 3)),
            ],
          ),
          child: Row(
            children: [
              IconButton(
                icon: Icon(Icons.arrow_back_ios_new, color: colors.primary, size: 18),
                tooltip: '뒤로가기',
                onPressed: () => Navigator.pop(context),
              ),
              CircleAvatar(
                radius: 18,
                backgroundColor: colors.primary.withAlpha(38),
                child: Icon(Icons.smart_toy_outlined, color: colors.primary, size: 20),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('FLOWER 챗봇', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
                    Text('${colors.name} 탐험 도우미', style: TextStyle(fontSize: 11, color: Colors.grey[500])),
                  ],
                ),
              ),
              IconButton(
                icon: Icon(Icons.refresh_rounded, color: colors.primary),
                tooltip: '대화 초기화',
                onPressed: _showResetDialog,
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _showResetDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('대화 초기화'),
        content: const Text('현재 대화 내용을 지우고 세션을 초기화할까요?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('취소')),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              _resetSession();
            },
            child: const Text('초기화', style: TextStyle(color: Color(0xFFE07B54))),
          ),
        ],
      ),
    );
  }

  Widget _buildAgentActivityPanel(AgentRunTrace agentRun, List<ToolResult> toolResults, SeasonColors colors) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      padding: const EdgeInsets.fromLTRB(14, 10, 14, 12),
      decoration: BoxDecoration(
        color: Colors.white.withAlpha(232),
        borderRadius: BorderRadius.circular(16),
        boxShadow: [BoxShadow(color: Colors.black.withAlpha(14), blurRadius: 10, offset: const Offset(0, 3))],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            'AI 작업 진행: ${_routeLabel(agentRun.route)}',
            style: TextStyle(color: colors.primary, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          ...agentRun.steps.take(6).map(
            (step) => Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${step.step}. ', style: TextStyle(color: colors.primary, fontWeight: FontWeight.w700)),
                  Expanded(
                    child: Text(
                      _stepLabel(step),
                      style: const TextStyle(color: Color(0xFF315C4B), fontSize: 12),
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (toolResults.isNotEmpty)
            Text(
              toolResults.map(_toolResultLabel).join(' / '),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: Color(0xFF5D6F66), fontSize: 12),
            ),
        ],
      ),
    );
  }

  String _routeLabel(String route) {
    if (route.isEmpty || route == 'GENERAL') return '일반 답변';
    return route.split('_').map(_targetLabel).join(' + ');
  }

  String _stepLabel(AgentStepTrace step) {
    final tool = _toolLabel(step.tool);
    final status = _statusLabel(step.status);
    final agent = _agentLabel(step.agent);
    if (tool.isNotEmpty) return '$agent이 $tool 작업을 $status.';
    if (step.message.isNotEmpty) return _messageLabel(step.message);
    return '$agent 작업 $status.';
  }

  String _toolResultLabel(ToolResult result) {
    final tool = _toolLabel(result.tool);
    final status = _statusLabel(result.status);
    final summary = _messageLabel(result.summary);
    if (summary.isEmpty) return '$tool $status';
    return '$tool $status: $summary';
  }

  String _agentLabel(String agent) {
    switch (agent) {
      case 'RouterAgent': return '라우팅 AI';
      case 'MapAgent': case 'MAPAgent': return '지도 AI';
      case 'FlowerAgent': case 'FLOWER_BOOKAgent': return '꽃 AI';
      case 'CommunityAgent': case 'COMMUNITYAgent': return '커뮤니티 AI';
      case 'WALKAgent': return '산책 AI';
      default: return '전문 AI';
    }
  }

  String _toolLabel(String tool) {
    switch (tool) {
      case 'routeAndPlan': return '의도 파악과 계획 수립';
      case 'searchFlowerSpots': case 'flower.searchFlowerSpots': return '꽃 명소 검색';
      case 'searchCommunityPosts': case 'community.searchPosts': return '커뮤니티 글 검색';
      case 'NAVIGATE': return '관련 화면 열기';
      case 'MAP_SET_SEARCH_QUERY': return '지도 검색어 적용';
      case 'MAP_SHOW_FLOWER': return '지도 꽃 위치 표시';
      case 'MAP_OPEN_FLOWER_PREVIEW': return '지도 꽃 미리보기';
      case 'PREPARE_DRAFT': return '커뮤니티 초안 준비';
      case 'buildDefaultContext': return '기본 정보 확인';
      default: return tool;
    }
  }

  String _statusLabel(String status) {
    switch (status) {
      case 'SUCCESS': return '완료했습니다';
      case 'READY': return '준비했습니다';
      case 'FAILED': return '실패했습니다';
      default: return status.isEmpty ? '진행했습니다' : status;
    }
  }

  String _targetLabel(String target) {
    switch (target) {
      case 'MAP': return '지도';
      case 'FLOWER': case 'FLOWER_BOOK': return '꽃';
      case 'COMMUNITY': return '커뮤니티';
      case 'WALK': return '산책';
      case 'QUEST': return '퀘스트';
      case 'SHOP': return '상점';
      default: return target;
    }
  }

  String _messageLabel(String message) {
    if (message.isEmpty) return '';
    final checked = RegExp(r'Checked (\d+) approved flower spot candidates\.').firstMatch(message);
    if (checked != null) return '승인된 꽃 명소 ${checked.group(1)}개를 확인했습니다.';
    final accepted = RegExp(r'Validated client-side follow-up from (.+)\.').firstMatch(message);
    if (accepted != null) return '${_plannerLabel(accepted.group(1) ?? '')}을 확인했습니다.';
    if (message == 'Selected a representative flower location for the map context.') return '대표 꽃 위치를 확인했습니다.';
    if (message == 'Built default flower and community context.') return '기본 꽃/커뮤니티 정보를 준비했습니다.';
    if (message == 'Checked default data for a general answer.') return '일반 답변에 필요한 기본 데이터를 확인했습니다.';
    final flowerSummary = RegExp(r"'(.+)' flower spot search returned (\d+) result\(s\)\.").firstMatch(message);
    if (flowerSummary != null) return "'${flowerSummary.group(1)}' 꽃 명소 검색 결과 ${flowerSummary.group(2)}개";
    final communitySummary = RegExp(r"'(.+)' community search returned (\d+) result\(s\)\.").firstMatch(message);
    if (communitySummary != null) return "'${communitySummary.group(1)}' 커뮤니티 검색 결과 ${communitySummary.group(2)}개";
    return message;
  }

  String _plannerLabel(String source) {
    switch (source) {
      case 'AIPlanner': return 'AI 계획';
      case 'FallbackPlanner': return '기본 계획';
      default: return source;
    }
  }

  Widget _buildInputArea(SeasonColors colors) {
    return Positioned(
      left: 0,
      right: 0,
      bottom: 0,
      child: SafeArea(
        child: Container(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
          decoration: BoxDecoration(
            color: Colors.white.withAlpha(235),
            borderRadius: BorderRadius.circular(24),
            boxShadow: [BoxShadow(color: Colors.black.withAlpha(22), blurRadius: 14, offset: const Offset(0, 4))],
          ),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _textController,
                  enabled: !_isLoading,
                  minLines: 1,
                  maxLines: 4,
                  decoration: const InputDecoration(
                    hintText: '메시지를 입력하세요',
                    hintStyle: TextStyle(color: Color(0xFFA0AAB2)),
                    border: InputBorder.none,
                    isDense: true,
                    contentPadding: EdgeInsets.symmetric(horizontal: 8, vertical: 12),
                  ),
                  onSubmitted: (_) => _sendMessage(),
                ),
              ),
              const SizedBox(width: 8),
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: _isLoading ? colors.primary.withAlpha(110) : colors.primary,
                  shape: BoxShape.circle,
                  boxShadow: [BoxShadow(color: colors.primary.withAlpha(72), blurRadius: 12, spreadRadius: 1)],
                ),
                child: IconButton(
                  icon: const Icon(Icons.send_rounded, color: Colors.white),
                  onPressed: _isLoading ? null : _sendMessage,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
