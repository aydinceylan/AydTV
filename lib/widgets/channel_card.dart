import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../models/channel.dart';
import '../theme/tv_theme.dart';

class ChannelCard extends StatefulWidget {
  final Channel channel;
  final int index;
  final bool isPlaying;
  final VoidCallback onSelect;
  final VoidCallback onToggleFavorite;
  final VoidCallback? onPrevCategory;
  final VoidCallback? onNextCategory;

  const ChannelCard({
    super.key,
    required this.channel,
    required this.index,
    required this.isPlaying,
    required this.onSelect,
    required this.onToggleFavorite,
    this.onPrevCategory,
    this.onNextCategory,
  });

  @override
  State<ChannelCard> createState() => _ChannelCardState();
}

class _ChannelCardState extends State<ChannelCard> {
  bool _isFocused = false;

  @override
  Widget build(BuildContext context) {
    return Focus(
      autofocus: widget.index == 0,
      onFocusChange: (focused) {
        setState(() {
          _isFocused = focused;
        });
      },
      onKeyEvent: (node, event) {
        if (event is! KeyDownEvent) return KeyEventResult.ignored;

        final key = event.logicalKey;

        // Kumanda OK Tuşuna Basıldığında Kanalı Aç
        if (key == LogicalKeyboardKey.select ||
            key == LogicalKeyboardKey.enter ||
            key == LogicalKeyboardKey.space ||
            key == LogicalKeyboardKey.gameButtonA) {
          widget.onSelect();
          return KeyEventResult.handled;
        }

        // Kategori Değiştirme (Sol / Sağ)
        if (key == LogicalKeyboardKey.arrowLeft) {
          widget.onPrevCategory?.call();
          return KeyEventResult.handled;
        }
        if (key == LogicalKeyboardKey.arrowRight) {
          widget.onNextCategory?.call();
          return KeyEventResult.handled;
        }

        return KeyEventResult.ignored;
      },
      child: GestureDetector(
        onTap: widget.onSelect,
        child: AnimatedScale(
          scale: _isFocused ? 1.03 : 1.0,
          duration: const Duration(milliseconds: 120),
          curve: Curves.easeOutCubic,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 120),
            margin: const EdgeInsets.symmetric(vertical: 3.5, horizontal: 8),
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 7),
            decoration: TVTheme.focusDecoration(
              isFocused: _isFocused,
              isPlaying: widget.isPlaying,
            ),
            child: Row(
              children: [
                // Kanal Numarası
                Container(
                  width: 26,
                  alignment: Alignment.center,
                  child: Text(
                    '${widget.index + 1}',
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.bold,
                      color: _isFocused
                          ? TVTheme.focusCyan
                          : TVTheme.textSecondary.withValues(alpha: 0.7),
                    ),
                  ),
                ),

                const SizedBox(width: 4),

                // Kanal Logosu
                Container(
                  width: 44,
                  height: 30,
                  padding: const EdgeInsets.all(2),
                  decoration: BoxDecoration(
                    color: Colors.black.withValues(alpha: 0.4),
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
                  ),
                  child: widget.channel.logo.isNotEmpty
                      ? CachedNetworkImage(
                          imageUrl: widget.channel.logo,
                          fit: BoxFit.contain,
                          placeholder: (context, url) => const Center(
                            child: SizedBox(
                              width: 12,
                              height: 12,
                              child: CircularProgressIndicator(
                                strokeWidth: 1.5,
                                color: TVTheme.focusCyan,
                              ),
                            ),
                          ),
                          errorWidget: (context, url, error) => const Icon(
                            Icons.tv,
                            color: TVTheme.textSecondary,
                            size: 18,
                          ),
                        )
                      : const Icon(
                          Icons.tv,
                          color: TVTheme.textSecondary,
                          size: 18,
                        ),
                ),

                const SizedBox(width: 8),

                // Kanal Adı + Mini EPG + İlerleme Çubuğu
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Row(
                        children: [
                          Flexible(
                            child: Text(
                              widget.channel.name,
                              style: TVTheme.tvChannelName.copyWith(
                                color: _isFocused ? Colors.white : TVTheme.textPrimary,
                                fontWeight: _isFocused ? FontWeight.bold : FontWeight.w600,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (widget.isPlaying) ...[
                            const SizedBox(width: 4),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                              decoration: BoxDecoration(
                                color: TVTheme.liveGreen.withValues(alpha: 0.25),
                                borderRadius: BorderRadius.circular(3),
                              ),
                              child: const Text(
                                'CANLI',
                                style: TextStyle(
                                  color: TVTheme.liveGreen,
                                  fontSize: 8,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 2),
                      // Mini EPG Yayın Bilgisi
                      Text(
                        widget.channel.currentProgram,
                        style: TVTheme.tvEpg,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      const SizedBox(height: 3),
                      // Mini EPG İlerleme Çubuğu
                      ClipRRect(
                        borderRadius: BorderRadius.circular(2),
                        child: LinearProgressIndicator(
                          value: widget.channel.programProgress,
                          minHeight: 2,
                          backgroundColor: Colors.white.withValues(alpha: 0.08),
                          valueColor: AlwaysStoppedAnimation<Color>(
                            _isFocused ? TVTheme.focusCyan : TVTheme.focusBlue.withValues(alpha: 0.7),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

                // Favori Yıldızı
                IconButton(
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(minWidth: 26, minHeight: 26),
                  icon: Icon(
                    widget.channel.isFavorite ? Icons.star_rounded : Icons.star_outline_rounded,
                    color: widget.channel.isFavorite ? TVTheme.favoriteGold : TVTheme.textSecondary.withValues(alpha: 0.5),
                    size: 20,
                  ),
                  onPressed: widget.onToggleFavorite,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
