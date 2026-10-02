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
  Channel? _focusedChannel;
  late ScrollController _scrollController;
  final FocusNode _emptyFocusNode = FocusNode();
  List<FocusNode> _channelFocusNodes = [];

  @override
  void initState() {
    super.initState();
    _focusedChannel = widget.currentChannel;
    _updateFocusNodes();

    final channels = _filteredChannels;
    int initialIdx = -1;
    if (widget.currentChannel != null) {
      initialIdx = channels.indexWhere((c) => c.id == widget.currentChannel!.id);
    }
    if (initialIdx == -1 && channels.isNotEmpty) {
      initialIdx = 0;
    }

    final double initialOffset = (initialIdx > 2) ? (initialIdx - 1) * 58.0 : 0.0;
    _scrollController = ScrollController(initialScrollOffset: initialOffset);

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (channels.isEmpty) {
        _emptyFocusNode.requestFocus();
      } else if (initialIdx >= 0 && initialIdx < _channelFocusNodes.length) {
        _channelFocusNodes[initialIdx].requestFocus();
        _focusedChannel = channels[initialIdx];
      }
    });
  }

  void _updateFocusNodes() {
    for (var node in _channelFocusNodes) {
      node.dispose();
    }
    final count = _filteredChannels.length;
    _channelFocusNodes = List.generate(
      count,
      (index) => FocusNode(debugLabel: 'ChannelNode_$index'),
    );
  }

  @override
  void dispose() {
    for (var node in _channelFocusNodes) {
      node.dispose();
    }
    _scrollController.dispose();
    _emptyFocusNode.dispose();
    super.dispose();
  }

  List<String> get _categories {
    final Set<String> cats = {'Tümü', '⭐ Favoriler'};
    for (var ch in widget.channels) {
      if (ch.category.trim().isNotEmpty) {
        cats.add(ch.category.trim());
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
        .where((ch) => ch.category.trim().toLowerCase() == _selectedCategory.trim().toLowerCase())
        .toList();
  }

  void _switchCategory(int direction) {
    final cats = _categories;
    int currentIdx = cats.indexOf(_selectedCategory);
    if (currentIdx == -1) currentIdx = 0;
    int nextIdx = (currentIdx + direction + cats.length) % cats.length;

    setState(() {
      _selectedCategory = cats[nextIdx];
      _updateFocusNodes();
    });

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final channels = _filteredChannels;
      if (channels.isEmpty) {
        _emptyFocusNode.requestFocus();
      } else {
        if (_channelFocusNodes.isNotEmpty) {
          _channelFocusNodes[0].requestFocus();
          _focusedChannel = channels[0];
        }
        if (_scrollController.hasClients) {
          _scrollController.jumpTo(0);
        }
      }
    });
  }

  void _handleChannelSelect(Channel ch) {
    if (widget.currentChannel?.id == ch.id) {
      // Zaten açık olan kanalda OK yapıldıysa menüyü kapat, tam ekrana geç
      widget.onClose();
    } else {
      // Başka bir kanala tıklandıysa kanalı aç ve arka planda oynat
      widget.onChannelSelect(ch);
    }
  }

  bool _isRedKey(KeyEvent event) {
    final key = event.logicalKey;
    if (key == LogicalKeyboardKey.f1 ||
        key == LogicalKeyboardKey.gameButtonX ||
        key.keyId == 183 ||
        key.keyId == 0x002000000b7 ||
        key.keyId == 0x00000000000000b7) {
      return true;
    }
    if (event.physicalKey == PhysicalKeyboardKey.f1) return true;
    if (key.keyLabel.toLowerCase().contains('red')) return true;
    return false;
  }

  @override
  Widget build(BuildContext context) {
    // 55" TV için zarif ve dar Liquid Glass genişliği (290px)
    const double drawerWidth = 290.0;
    final channels = _filteredChannels;

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) {
          widget.onClose();
        }
      },
      child: Focus(
        canRequestFocus: false,
        skipTraversal: true,
        onKeyEvent: (node, event) {
          if (event is! KeyDownEvent) return KeyEventResult.ignored;

          final key = event.logicalKey;

          // Geri Tuşu: Menüyü kapatır
          if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
            widget.onClose();
            return KeyEventResult.handled;
          }

          // Kumanda Kırmızı Tuş: Menüde üzerinde bulunulan kanalı favoriye ekler/çıkarır
          if (_isRedKey(event)) {
            final target = _focusedChannel ?? widget.currentChannel;
            if (target != null) {
              widget.onToggleFavorite(target);
              setState(() {});
              return KeyEventResult.handled;
            }
          }

          // Kategori Değiştirme (Sol / Sağ)
          if (key == LogicalKeyboardKey.arrowLeft) {
            _switchCategory(-1);
            return KeyEventResult.handled;
          }
          if (key == LogicalKeyboardKey.arrowRight) {
            _switchCategory(1);
            return KeyEventResult.handled;
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
                        padding: const EdgeInsets.fromLTRB(14, 12, 14, 6),
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
                              '${channels.length}',
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
                                  _updateFocusNodes();
                                });
                                WidgetsBinding.instance.addPostFrameCallback((_) {
                                  if (!mounted) return;
                                  if (_filteredChannels.isEmpty) {
                                    _emptyFocusNode.requestFocus();
                                  } else if (_channelFocusNodes.isNotEmpty) {
                                    _channelFocusNodes[0].requestFocus();
                                  }
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

                      const Divider(color: Colors.white10, height: 10),

                      // Kanal Listesi veya Boş Durum
                      Expanded(
                        child: channels.isEmpty
                            ? Focus(
                                focusNode: _emptyFocusNode,
                                onKeyEvent: (node, event) {
                                  if (event is! KeyDownEvent) return KeyEventResult.ignored;
                                  if (event.logicalKey == LogicalKeyboardKey.arrowLeft) {
                                    _switchCategory(-1);
                                    return KeyEventResult.handled;
                                  }
                                  if (event.logicalKey == LogicalKeyboardKey.arrowRight) {
                                    _switchCategory(1);
                                    return KeyEventResult.handled;
                                  }
                                  if (event.logicalKey == LogicalKeyboardKey.escape ||
                                      event.logicalKey == LogicalKeyboardKey.goBack) {
                                    widget.onClose();
                                    return KeyEventResult.handled;
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
                                controller: _scrollController,
                                padding: const EdgeInsets.only(top: 6, bottom: 20),
                                itemCount: channels.length,
                                itemBuilder: (context, index) {
                                  final ch = channels[index];
                                  final isPlaying = widget.currentChannel?.id == ch.id;
                                  final fNode = (index < _channelFocusNodes.length)
                                      ? _channelFocusNodes[index]
                                      : null;

                                  return ChannelCard(
                                    key: ValueKey('${ch.id}_${_selectedCategory}_$index'),
                                    channel: ch,
                                    index: index,
                                    isPlaying: isPlaying,
                                    focusNode: fNode,
                                    onSelect: () => _handleChannelSelect(ch),
                                    onToggleFavorite: () {
                                      widget.onToggleFavorite(ch);
                                      setState(() {});
                                    },
                                    onFocusChanged: (focused) {
                                      if (focused) {
                                        _focusedChannel = ch;
                                      }
                                    },
                                    onPrevCategory: () => _switchCategory(-1),
                                    onNextCategory: () => _switchCategory(1),
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
                              'OK: İzle/Kapat • ◀ ▶: Kategori • 🔴: Favori',
                              style: TextStyle(color: TVTheme.textSecondary, fontSize: 10),
                            ),
                            Text(
                              'v1.0.4',
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
      ),
    );
  }
}
