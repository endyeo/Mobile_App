import 'package:flutter/material.dart';
import 'chat_screen.dart';
import 'pedometer_screen.dart';
import 'community_feed_screen.dart';
import 'flower_book_page.dart';
import 'saved_page.dart';

class ChatbotScreen extends StatelessWidget {
  const ChatbotScreen({super.key});
  @override
  Widget build(BuildContext context) => const ChatScreen();
}

class CommunityScreen extends StatelessWidget {
  const CommunityScreen({super.key});
  @override
  Widget build(BuildContext context) => const CommunityFeedScreen();
}

class WalkScreen extends StatelessWidget {
  const WalkScreen({super.key});
  @override
  Widget build(BuildContext context) => const PedometerScreen();
}

class FlowerBookScreen extends StatelessWidget {
  const FlowerBookScreen({super.key});
  @override
  Widget build(BuildContext context) => const FlowerBookPage();
}

class SavedScreen extends StatelessWidget {
  const SavedScreen({super.key});
  @override
  Widget build(BuildContext context) => const SavedPage();
}
