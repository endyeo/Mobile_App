import 'package:flutter/material.dart';
import '../theme/season_theme.dart';
import '../services/flower_book_api_service.dart';

class FlowerBookPage extends StatefulWidget {
  const FlowerBookPage({
    super.key,
    this.initialQuery,
    this.initialFlowerBookId,
  });

  final String? initialQuery;
  final int? initialFlowerBookId;

  @override
  State<FlowerBookPage> createState() => _FlowerBookPageState();
}

class _FlowerBookPageState extends State<FlowerBookPage> {
  List<FlowerBookItem> _flowers = [];
  bool _isLoading = true;
  String? _error;
  int _selectedMonth = DateTime.now().month;
  final TextEditingController _searchController = TextEditingController();
  bool _isSearching = false;

  @override
  void initState() {
    super.initState();
    final initialQuery = widget.initialQuery?.trim();
    if (initialQuery != null && initialQuery.isNotEmpty) {
      _searchController.text = initialQuery;
      _search(initialQuery);
    } else {
      _loadFlowers();
    }
    _openInitialFlowerIfNeeded();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadFlowers() async {
    setState(() {
      _isLoading = true;
      _error = null;
      _isSearching = false;
    });
    try {
      final flowers = await FlowerBookApiService.getByMonth(_selectedMonth);
      if (mounted)
        setState(() {
          _flowers = flowers;
          _isLoading = false;
        });
    } catch (e) {
      if (mounted)
        setState(() {
          _error = e.toString();
          _isLoading = false;
        });
    }
  }

  Future<void> _search(String keyword) async {
    if (keyword.trim().isEmpty) {
      _loadFlowers();
      return;
    }
    setState(() {
      _isLoading = true;
      _isSearching = true;
    });
    try {
      final results = await FlowerBookApiService.search(keyword.trim());
      if (mounted)
        setState(() {
          _flowers = results;
          _isLoading = false;
        });
    } catch (e) {
      if (mounted)
        setState(() {
          _error = e.toString();
          _isLoading = false;
          _isSearching = false;
        });
    }
  }

  Future<void> _openInitialFlowerIfNeeded() async {
    final flowerId = widget.initialFlowerBookId;
    if (flowerId == null) return;
    try {
      final detail = await FlowerBookApiService.getDetail(flowerId);
      if (!mounted) return;
      final item = FlowerBookItem(
        id: detail.id,
        name: detail.name,
        categoryName: detail.categoryName,
        categoryEmoji: detail.categoryEmoji,
        bloomMonth: detail.bloomMonth,
        bloomDay: detail.bloomDay,
        flowerLanguage: detail.flowerLanguage,
        imageUrl: detail.imageUrl,
      );
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _showFlowerDetail(item, SeasonTheme.getColors());
      });
    } catch (_) {
      // 상세 이동은 보조 동작이므로 실패해도 목록 화면은 유지한다.
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    return Scaffold(
      backgroundColor: colors.background,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new, color: colors.primary, size: 18),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text(
          '꽃 도감',
          style: TextStyle(color: colors.primary, fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            icon: Icon(Icons.search, color: colors.primary),
            onPressed: () => _showSearch(colors),
          ),
        ],
      ),
      body: Column(
        children: [
          if (!_isSearching) _buildMonthSelector(colors),
          Expanded(
            child: _isLoading
                ? Center(
                    child: CircularProgressIndicator(color: colors.primary),
                  )
                : _error != null
                ? _buildErrorView(colors)
                : _flowers.isEmpty
                ? Center(
                    child: Text(
                      '해당 월의 꽃 정보가 없습니다',
                      style: TextStyle(color: Colors.grey[500]),
                    ),
                  )
                : _buildFlowerGrid(colors),
          ),
        ],
      ),
    );
  }

  void _showSearch(SeasonColors colors) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.of(ctx).viewInsets.bottom,
          left: 16,
          right: 16,
          top: 16,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _searchController,
              autofocus: true,
              decoration: InputDecoration(
                hintText: '꽃 이름으로 검색',
                prefixIcon: Icon(Icons.search, color: colors.primary),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              onSubmitted: (v) {
                Navigator.pop(ctx);
                _search(v);
              },
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton(
                    onPressed: () {
                      Navigator.pop(ctx);
                      _search(_searchController.text);
                    },
                    style: ElevatedButton.styleFrom(
                      backgroundColor: colors.primary,
                    ),
                    child: const Text(
                      '검색',
                      style: TextStyle(color: Colors.white),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                TextButton(
                  onPressed: () {
                    Navigator.pop(ctx);
                    _searchController.clear();
                    _loadFlowers();
                  },
                  child: const Text('초기화'),
                ),
              ],
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  Widget _buildMonthSelector(SeasonColors colors) {
    return SizedBox(
      height: 50,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        itemCount: 12,
        itemBuilder: (context, i) {
          final m = i + 1;
          final isSelected = m == _selectedMonth;
          return GestureDetector(
            onTap: () {
              setState(() => _selectedMonth = m);
              _loadFlowers();
            },
            child: Container(
              width: 44,
              margin: const EdgeInsets.symmetric(horizontal: 3),
              decoration: BoxDecoration(
                color: isSelected ? colors.primary : Colors.white,
                borderRadius: BorderRadius.circular(14),
                boxShadow: [
                  BoxShadow(
                    color: isSelected
                        ? colors.primary.withAlpha(40)
                        : Colors.grey.withAlpha(20),
                    blurRadius: isSelected ? 8 : 4,
                  ),
                ],
              ),
              child: Center(
                child: Text(
                  '${m}월',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: isSelected
                        ? FontWeight.bold
                        : FontWeight.normal,
                    color: isSelected ? Colors.white : Colors.grey[600],
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildFlowerGrid(SeasonColors colors) {
    return GridView.builder(
      padding: const EdgeInsets.all(10),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        childAspectRatio: 0.68,
        crossAxisSpacing: 8,
        mainAxisSpacing: 8,
      ),
      itemCount: _flowers.length,
      itemBuilder: (context, i) => _buildFlowerCard(_flowers[i], colors),
    );
  }

  Widget _buildFlowerCard(FlowerBookItem flower, SeasonColors colors) {
    final emoji = flower.categoryEmoji ?? '🌿';
    final cardColor = _categoryColor(flower.categoryName);
    return GestureDetector(
      onTap: () => _showFlowerDetail(flower, colors),
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(14),
          boxShadow: [
            BoxShadow(
              color: cardColor.withAlpha(30),
              blurRadius: 6,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Column(
          children: [
            Expanded(
              flex: 3,
              child: Container(
                width: double.infinity,
                decoration: BoxDecoration(
                  color: cardColor.withAlpha(30),
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(14),
                  ),
                ),
                child: flower.imageUrl != null && flower.imageUrl!.isNotEmpty
                    ? ClipRRect(
                        borderRadius: const BorderRadius.vertical(
                          top: Radius.circular(14),
                        ),
                        child: Image.network(
                          flower.imageUrl!,
                          fit: BoxFit.cover,
                          cacheWidth: 300,
                          filterQuality: FilterQuality.medium,
                          errorBuilder: (_, __, ___) => Center(
                            child: Text(
                              emoji,
                              style: const TextStyle(fontSize: 32),
                            ),
                          ),
                        ),
                      )
                    : Center(
                        child: Text(
                          emoji,
                          style: const TextStyle(fontSize: 32),
                        ),
                      ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 5),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    flower.name,
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 1),
                  Text(
                    flower.flowerLanguage ?? '',
                    style: TextStyle(fontSize: 9, color: Colors.grey[500]),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _showFlowerDetail(FlowerBookItem flower, SeasonColors colors) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => _FlowerDetailSheet(
        flower: flower,
        colors: colors,
        cardColor: _categoryColor(flower.categoryName),
      ),
    );
  }

  Color _categoryColor(String? category) {
    if (category == null) return const Color(0xFF81C784);
    if (category.contains('벚꽃')) return const Color(0xFFFFB7C5);
    if (category.contains('장미')) return const Color(0xFFEC407A);
    if (category.contains('튤립')) return const Color(0xFFFF6B6B);
    if (category.contains('해바라기')) return const Color(0xFFFFCA28);
    if (category.contains('국화')) return const Color(0xFFFFA726);
    if (category.contains('코스모스')) return const Color(0xFFFF8A65);
    if (category.contains('수국')) return const Color(0xFF7E57C2);
    if (category.contains('동백')) return const Color(0xFFE53935);
    if (category.contains('라벤더')) return const Color(0xFFAB47BC);
    if (category.contains('무궁화')) return const Color(0xFFE91E63);
    if (category.contains('매화')) return const Color(0xFFBDBDBD);
    if (category.contains('진달래')) return const Color(0xFFE8A0BF);
    return const Color(0xFF81C784);
  }

  Widget _buildErrorView(SeasonColors colors) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.cloud_off, size: 48, color: Colors.grey[400]),
          const SizedBox(height: 12),
          Text('데이터를 불러오지 못했습니다', style: TextStyle(color: Colors.grey[500])),
          const SizedBox(height: 8),
          ElevatedButton(
            onPressed: _loadFlowers,
            style: ElevatedButton.styleFrom(backgroundColor: colors.primary),
            child: const Text('다시 시도', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );
  }
}

class _FlowerDetailSheet extends StatefulWidget {
  final FlowerBookItem flower;
  final SeasonColors colors;
  final Color cardColor;

  const _FlowerDetailSheet({
    required this.flower,
    required this.colors,
    required this.cardColor,
  });

  @override
  State<_FlowerDetailSheet> createState() => _FlowerDetailSheetState();
}

class _FlowerDetailSheetState extends State<_FlowerDetailSheet> {
  FlowerBookDetail? _detail;
  bool _loadingDetail = true;

  @override
  void initState() {
    super.initState();
    _loadDetail();
  }

  Future<void> _loadDetail() async {
    try {
      final detail = await FlowerBookApiService.getDetail(widget.flower.id);
      if (mounted)
        setState(() {
          _detail = detail;
          _loadingDetail = false;
        });
    } catch (e) {
      if (mounted) setState(() => _loadingDetail = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final emoji = widget.flower.categoryEmoji ?? '🌿';
    final imageUrl = _detail?.imageUrl ?? widget.flower.imageUrl;

    return SafeArea(
      top: false,
      child: Container(
        height: MediaQuery.of(context).size.height * 0.7,
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
        ),
        child: Column(
          children: [
            Container(
              margin: const EdgeInsets.only(top: 12),
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: Colors.grey[300],
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Container(
              width: double.infinity,
              height: 160,
              margin: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: widget.cardColor.withAlpha(40),
                borderRadius: BorderRadius.circular(20),
              ),
              child: imageUrl != null && imageUrl.isNotEmpty
                  ? ClipRRect(
                      borderRadius: BorderRadius.circular(20),
                      child: Image.network(
                        imageUrl,
                        fit: BoxFit.cover,
                        cacheWidth: 800,
                        filterQuality: FilterQuality.medium,
                        errorBuilder: (_, __, ___) => Center(
                          child: Text(
                            emoji,
                            style: const TextStyle(fontSize: 72),
                          ),
                        ),
                      ),
                    )
                  : Center(
                      child: Text(emoji, style: const TextStyle(fontSize: 72)),
                    ),
            ),
            Expanded(
              child: _loadingDetail
                  ? Center(
                      child: CircularProgressIndicator(
                        color: widget.colors.primary,
                      ),
                    )
                  : ListView(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      children: [
                        Text(
                          _detail?.name ?? widget.flower.name,
                          style: const TextStyle(
                            fontSize: 24,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        if (_detail?.scientificName?.isNotEmpty == true)
                          Text(
                            _detail!.scientificName!,
                            style: TextStyle(
                              fontSize: 14,
                              color: Colors.grey[500],
                              fontStyle: FontStyle.italic,
                            ),
                          ),
                        const SizedBox(height: 12),
                        _infoChip(
                          Icons.format_quote,
                          '꽃말',
                          _detail?.flowerLanguage ??
                              widget.flower.flowerLanguage ??
                              '',
                        ),
                        _infoChip(
                          Icons.calendar_today,
                          '개화',
                          widget.flower.dateString,
                        ),
                        if (_detail?.description?.isNotEmpty == true) ...[
                          const SizedBox(height: 16),
                          _sectionTitle('꽃 이야기'),
                          Text(
                            _detail!.description!,
                            style: const TextStyle(fontSize: 14, height: 1.6),
                          ),
                        ],
                        if (_detail?.growTips?.isNotEmpty == true) ...[
                          const SizedBox(height: 16),
                          _sectionTitle('기르기'),
                          Text(
                            _detail!.growTips!,
                            style: const TextStyle(fontSize: 14, height: 1.6),
                          ),
                        ],
                        const SizedBox(height: 24),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _infoChip(IconData icon, String label, String value) {
    if (value.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 16, color: widget.colors.primary),
          const SizedBox(width: 8),
          Text(
            '$label: ',
            style: TextStyle(fontSize: 13, color: Colors.grey[600]),
          ),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }

  Widget _sectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(
        title,
        style: TextStyle(
          fontSize: 15,
          fontWeight: FontWeight.bold,
          color: widget.colors.primary,
        ),
      ),
    );
  }
}
