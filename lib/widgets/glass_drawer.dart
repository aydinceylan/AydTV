import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/channel.dart';
import '../theme/tv_theme.dart';
import 'channel_card.dart';

class GlassDrawer extends StatefulWidget {
  final List<Channel> channels;
  final Channel? currentChannel;
  final Function(Channel) onChannelSelect;
  final Function(Channel) onToggleFavorite;
  final VoidCallback onClose;

  const GlassDrawer({
    super.key,
    required this.channels,
    required this.currentChannel,
    required this.onChannelSelect,
    required this.onToggleFavorite,
    required this.onClose,
  });

  @override
  State<GlassDrawer> createState() => _GlassDrawerState();
}

class _GlassDrawerState extends State<GlassDrawer> {
  String _selectedCategory = 'Tümü';

  List<String> get _categories {
    final Set<String> cats = {'Tümü', '⭐ Favoriler'};
    for (var ch in widget.channels) {
      if (ch.category.isNotEmpty) {
        cats.add(ch.category);
      }
    }
    return cats.toList();
  }

  List<Channel> get _filteredChannels {
    if (_selectedCategory == '⭐ Favoriler') {
      return widget.channels.where((ch) => ch.isFavorite).toList();
    }
    if (_selectedCategory == 'Tümü') {
      return widget.channels;
    }
    return widget.channels
        .where((ch) => ch.category.toLowerCase() == _selectedCategory.toLowerCase())
        .toList();
  }

  void _nextCategory() {
    final cats = _categories;
    int currentIdx = cats.indexOf(_selectedCategory);
    int nextIdx = (currentIdx + 1) % cats.length;
    setState(() {
      _selectedCategory = cats[nextIdx];
    });
  }

  void _prevCategory() {
    final cats = _categories;
    int currentIdx = cats.indexOf(_selectedCategory);
    int prevIdx = (currentIdx - 1 + cats.length) % cats.length;
    setState(() {
      _selectedCategory = cats[prevIdx];
    });
  }

  @override
  Widget build(BuildContext context) {
    const double drawerWidth = 360.0;

    return Focus(
      onKeyEvent: (node, event) {
        if (event is KeyDownEvent) {
          if (event.logicalKey == LogicalKeyboardKey.escape ||
              event.logicalKey == LogicalKeyboardKey.goBack) {
            widget.onClose();
            return KeyEventResult.handled;
          }
        }
        return KeyEventResult.ignored;
      },
      child: Stack(
      children: [
        // Arka Plan Cam Bulanıklığı (Hafifletilmiş 7px Gauss Bulanıklığı)
        GestureDetector(
          onTap: widget.onClose,
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 7.0, sigmaY: 7.0),
            child: Container(
              color: Colors.black.withValues(alpha: 0.30),
            ),
          ),
        ),

        // Sol Cam Menü Paneli (360px İnce Şık TV Kenarlığı)
        Align(
          alignment: Alignment.centerLeft,
          child: Container(
            width: drawerWidth,
            height: double.infinity,
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [
                  TVTheme.surfaceGlass,
                  const Color(0xF2090C10),
                ],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              border: Border(
                right: BorderSide(
                  color: TVTheme.focusCyan.withValues(alpha: 0.25),
                  width: 1.5,
                ),
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.7),
                  blurRadius: 24,
                  spreadRadius: 4,
                ),
              ],
            ),
            child: SafeArea(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Başlık Alanı
                  Padding(
                    padding: const EdgeInsets.fromLTRB(18, 16, 18, 8),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Row(
                          children: [
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                              decoration: BoxDecoration(
                                gradient: const LinearGradient(
                                  colors: [TVTheme.focusCyan, TVTheme.focusBlue],
                                ),
                                borderRadius: BorderRadius.circular(6),
                              ),
                              child: const Text(
                                'AydTV',
                                style: TextStyle(
                                  color: Colors.black,
                                  fontWeight: FontWeight.w900,
                                  fontSize: 16,
                                ),
                              ),
                            ),
                            const SizedBox(width: 10),
                            const Text(
                              'Kanallar',
                              style: TVTheme.tvTitle,
                            ),
                          ],
                        ),
                        Text(
                          '${_filteredChannels.length} Kanal',
                          style: TVTheme.tvCategory,
                        ),
                      ],
                    ),
                  ),

                  // Kategori Hapları
                  SizedBox(
                    height: 40,
                    child: ListView.builder(
                      scrollDirection: Axis.horizontal,
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      itemCount: _categories.length,
                      itemBuilder: (context, idx) {
                        final cat = _categories[idx];
                        final isSelected = cat == _selectedCategory;

                        return GestureDetector(
                          onTap: () {
                            setState(() {
                              _selectedCategory = cat;
                            });
                          },
                          child: AnimatedContainer(
                            duration: const Duration(milliseconds: 140),
                            margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 3),
                            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                            decoration: BoxDecoration(
                              color: isSelected
                                  ? TVTheme.focusCyan.withValues(alpha: 0.22)
                                  : Colors.white.withValues(alpha: 0.05),
                              borderRadius: BorderRadius.circular(16),
                              border: Border.all(
                                color: isSelected ? TVTheme.focusCyan : Colors.transparent,
                                width: 1.2,
                              ),
                            ),
                            child: Center(
                              child: Text(
                                cat,
                                style: TextStyle(
                                  fontSize: 13,
                                  fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
                                  color: isSelected ? TVTheme.focusCyan : TVTheme.textPrimary,
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                  ),

                  const Divider(color: Colors.white12, height: 14),

                  // Kanal Listesi
                  Expanded(
                    child: _filteredChannels.isEmpty
                        ? const Center(
                            child: Text(
                              'Bu kategoride kanal bulunamadı.',
                              style: TextStyle(color: TVTheme.textSecondary, fontSize: 14),
                            ),
                          )
                        : ListView.builder(
                            padding: const EdgeInsets.only(bottom: 16),
                            itemCount: _filteredChannels.length,
                            itemBuilder: (context, index) {
                              final ch = _filteredChannels[index];
                              final isPlaying = widget.currentChannel?.id == ch.id;

                              return ChannelCard(
                                key: ValueKey(ch.id),
                                channel: ch,
                                index: index,
                                isPlaying: isPlaying,
                                onSelect: () {
                                  widget.onChannelSelect(ch);
                                  widget.onClose();
                                },
                                onToggleFavorite: () {
                                  widget.onToggleFavorite(ch);
                                  setState(() {});
                                },
                                onPrevCategory: _prevCategory,
                                onNextCategory: _nextCategory,
                              );
                            },
                          ),
                  ),

                  // Alt Bilgilendirme
                  Container(
                    padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
                    color: Colors.black.withValues(alpha: 0.45),
                    child: const Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'OK: İzle • ◀ ▶: Kategori • 🔴: Favori',
                          style: TextStyle(color: TVTheme.textSecondary, fontSize: 11),
                        ),
                        Text(
                          'v1.0.0',
                          style: TextStyle(color: Colors.white24, fontSize: 11),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    ),
  );
}
}
