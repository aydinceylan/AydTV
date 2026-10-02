import 'dart:ui';
import 'package:flutter/material.dart';
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

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.of(context).size.width;
    final drawerWidth = screenWidth * 0.42 > 460 ? 460.0 : screenWidth * 0.42;

    return Stack(
      children: [
        // Arka Plan Cam Bulanıklığı (Yayın Arkada Akmaya Devam Eder)
        GestureDetector(
          onTap: widget.onClose,
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
            child: Container(
              color: Colors.black.withValues(alpha: 0.45),
            ),
          ),
        ),

        // Sol Cam Menü Paneli
        Align(
          alignment: Alignment.centerLeft,
          child: Container(
            width: drawerWidth,
            height: double.infinity,
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [
                  TVTheme.surfaceGlass,
                  const Color(0xF00D1117),
                ],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              border: Border(
                right: BorderSide(
                  color: Colors.white.withValues(alpha: 0.12),
                  width: 1,
                ),
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.6),
                  blurRadius: 30,
                  spreadRadius: 10,
                ),
              ],
            ),
            child: SafeArea(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Başlık Alanı
                  Padding(
                    padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Row(
                          children: [
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                              decoration: BoxDecoration(
                                gradient: const LinearGradient(
                                  colors: [TVTheme.focusCyan, TVTheme.focusBlue],
                                ),
                                borderRadius: BorderRadius.circular(8),
                              ),
                              child: const Text(
                                'AydTV',
                                style: TextStyle(
                                  color: Colors.black,
                                  fontWeight: FontWeight.w900,
                                  fontSize: 20,
                                ),
                              ),
                            ),
                            const SizedBox(width: 12),
                            Text(
                              'Kanal Listesi',
                              style: TVTheme.tvTitle.copyWith(fontSize: 22),
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

                  // Kategori Hapları (D-Pad ile Sağ/Sol Gezinilebilir)
                  SizedBox(
                    height: 48,
                    child: ListView.builder(
                      scrollDirection: Axis.horizontal,
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      itemCount: _categories.length,
                      itemBuilder: (context, idx) {
                        final cat = _categories[idx];
                        final isSelected = cat == _selectedCategory;

                        return Focus(
                          onFocusChange: (focused) {
                            if (focused) {
                              setState(() {
                                _selectedCategory = cat;
                              });
                            }
                          },
                          child: GestureDetector(
                            onTap: () {
                              setState(() {
                                _selectedCategory = cat;
                              });
                            },
                            child: AnimatedContainer(
                              duration: const Duration(milliseconds: 180),
                              margin: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                              decoration: BoxDecoration(
                                color: isSelected
                                    ? TVTheme.focusCyan.withValues(alpha: 0.2)
                                    : Colors.white.withValues(alpha: 0.05),
                                borderRadius: BorderRadius.circular(20),
                                border: Border.all(
                                  color: isSelected ? TVTheme.focusCyan : Colors.transparent,
                                  width: 1.5,
                                ),
                              ),
                              child: Center(
                                child: Text(
                                  cat,
                                  style: TextStyle(
                                    fontSize: 15,
                                    fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
                                    color: isSelected ? TVTheme.focusCyan : TVTheme.textPrimary,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                  ),

                  const Divider(color: Colors.white12, height: 20),

                  // Kanal Listesi
                  Expanded(
                    child: _filteredChannels.isEmpty
                        ? const Center(
                            child: Text(
                              'Bu kategoride kanal bulunamadı.',
                              style: TextStyle(color: TVTheme.textSecondary, fontSize: 16),
                            ),
                          )
                        : ListView.builder(
                            padding: const EdgeInsets.only(bottom: 24),
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
                                  setState(() {
                                    widget.onToggleFavorite(ch);
                                  });
                                },
                              );
                            },
                          ),
                  ),

                  // Alt Bilgilendirme
                  Container(
                    padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 24),
                    color: Colors.black.withValues(alpha: 0.4),
                    child: const Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          '🎮 Kumanda: OK Seç • Geri Kapat',
                          style: TextStyle(color: TVTheme.textSecondary, fontSize: 13),
                        ),
                        Text(
                          'v1.0.0',
                          style: TextStyle(color: Colors.white24, fontSize: 12),
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
    );
  }
}
