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
    // 55" TV için zarif ve dar Liquid Glass genişliği (290px)
    const double drawerWidth = 290.0;

    return Focus(
      autofocus: true,
      onKeyEvent: (node, event) {
        if (event is KeyDownEvent) {
          // Geri Tuşu: Kesinlikle uygulamayı kapatmaz, sadece menüyü kapatır!
          if (event.logicalKey == LogicalKeyboardKey.escape ||
              event.logicalKey == LogicalKeyboardKey.goBack) {
            widget.onClose();
            return KeyEventResult.handled;
          }
          // Boş kategorideyken dahi sağ/sol ile kategorileri gezin
          if (event.logicalKey == LogicalKeyboardKey.arrowLeft) {
            _prevCategory();
            return KeyEventResult.handled;
          }
          if (event.logicalKey == LogicalKeyboardKey.arrowRight) {
            _nextCategory();
            return KeyEventResult.handled;
          }
        }
        return KeyEventResult.ignored;
      },
      child: Stack(
        children: [
          // Arka Plan Hafif Liquid Glass Bulanıklığı (Sadece 5px)
          GestureDetector(
            onTap: widget.onClose,
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 5.0, sigmaY: 5.0),
              child: Container(
                color: Colors.black.withValues(alpha: 0.25),
              ),
            ),
          ),

          // Sol Liquid Glass Yan Panel
          Align(
            alignment: Alignment.centerLeft,
            child: Container(
              width: drawerWidth,
              height: double.infinity,
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [
                    Color(0xB8070B12),
                    Color(0xCC0B101B),
                  ],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                border: Border(
                  right: BorderSide(
                    color: TVTheme.focusCyan.withValues(alpha: 0.3),
                    width: 1.2,
                  ),
                ),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.6),
                    blurRadius: 20,
                    spreadRadius: 2,
                  ),
                ],
              ),
              child: SafeArea(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Başlık & Logo
                    Padding(
                      padding: const EdgeInsets.fromLTRB(14, 14, 14, 6),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Row(
                            children: [
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2.5),
                                decoration: BoxDecoration(
                                  gradient: const LinearGradient(
                                    colors: [TVTheme.focusCyan, TVTheme.focusBlue],
                                  ),
                                  borderRadius: BorderRadius.circular(5),
                                ),
                                child: const Text(
                                  'AydTV',
                                  style: TextStyle(
                                    color: Colors.black,
                                    fontWeight: FontWeight.w900,
                                    fontSize: 14,
                                  ),
                                ),
                              ),
                              const SizedBox(width: 8),
                              const Text(
                                'Kanallar',
                                style: TVTheme.tvTitle,
                              ),
                            ],
                          ),
                          Text(
                            '${_filteredChannels.length}',
                            style: TVTheme.tvCategory.copyWith(fontWeight: FontWeight.bold),
                          ),
                        ],
                      ),
                    ),

                    // Kategori Hapları
                    SizedBox(
                      height: 36,
                      child: ListView.builder(
                        scrollDirection: Axis.horizontal,
                        padding: const EdgeInsets.symmetric(horizontal: 10),
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
                              duration: const Duration(milliseconds: 120),
                              margin: const EdgeInsets.symmetric(horizontal: 3, vertical: 2),
                              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                              decoration: BoxDecoration(
                                color: isSelected
                                    ? TVTheme.focusCyan.withValues(alpha: 0.25)
                                    : Colors.white.withValues(alpha: 0.04),
                                borderRadius: BorderRadius.circular(14),
                                border: Border.all(
                                  color: isSelected ? TVTheme.focusCyan : Colors.transparent,
                                  width: 1.0,
                                ),
                              ),
                              child: Center(
                                child: Text(
                                  cat,
                                  style: TextStyle(
                                    fontSize: 12,
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

                    const Divider(color: Colors.white10, height: 12),

                    // Kanal Listesi veya Boş Durum
                    Expanded(
                      child: _filteredChannels.isEmpty
                          ? Focus(
                              autofocus: true,
                              onKeyEvent: (node, event) {
                                if (event is KeyDownEvent) {
                                  if (event.logicalKey == LogicalKeyboardKey.arrowLeft) {
                                    _prevCategory();
                                    return KeyEventResult.handled;
                                  }
                                  if (event.logicalKey == LogicalKeyboardKey.arrowRight) {
                                    _nextCategory();
                                    return KeyEventResult.handled;
                                  }
                                }
                                return KeyEventResult.ignored;
                              },
                              child: Center(
                                child: Container(
                                  margin: const EdgeInsets.all(16),
                                  padding: const EdgeInsets.all(16),
                                  decoration: BoxDecoration(
                                    color: Colors.white.withValues(alpha: 0.05),
                                    borderRadius: BorderRadius.circular(12),
                                    border: Border.all(color: Colors.white12),
                                  ),
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      const Icon(Icons.star_outline_rounded,
                                          color: TVTheme.favoriteGold, size: 36),
                                      const SizedBox(height: 8),
                                      const Text(
                                        'Henüz Favori Eklenmedi',
                                        style: TextStyle(
                                          color: Colors.white,
                                          fontWeight: FontWeight.bold,
                                          fontSize: 14,
                                        ),
                                        textAlign: TextAlign.center,
                                      ),
                                      const SizedBox(height: 4),
                                      const Text(
                                        'Kanallara Kırmızı tuşla favori ekleyebilirsiniz.\n◀ ▶ ile diğer kategorilere geçin.',
                                        style: TextStyle(color: TVTheme.textSecondary, fontSize: 11),
                                        textAlign: TextAlign.center,
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            )
                          : ListView.builder(
                              padding: const EdgeInsets.only(bottom: 12),
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

                    // Alt İpucu Barı
                    Container(
                      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 12),
                      color: Colors.black.withValues(alpha: 0.4),
                      child: const Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            'OK: İzle • ◀ ▶: Kategori • 🔴: Favori',
                            style: TextStyle(color: TVTheme.textSecondary, fontSize: 10),
                          ),
                          Text(
                            'v1.0.2',
                            style: TextStyle(color: Colors.white24, fontSize: 10),
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
