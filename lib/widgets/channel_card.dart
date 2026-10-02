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

        // Herhangi bir kanalda sağ/sol yapılınca kategoriyi değiştir
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
          scale: _isFocused ? 1.04 : 1.0,
          duration: const Duration(milliseconds: 140),
          curve: Curves.easeOutCubic,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 140),
            margin: const EdgeInsets.symmetric(vertical: 4, horizontal: 8),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            decoration: TVTheme.focusDecoration(
              isFocused: _isFocused,
              isPlaying: widget.isPlaying,
            ),
            child: Row(
              children: [
                // Kanal Numarası
                Container(
                  width: 34,
                  alignment: Alignment.center,
                  child: Text(
                    '${widget.index + 1}',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                      color: _isFocused
                          ? TVTheme.focusCyan
                          : TVTheme.textSecondary.withValues(alpha: 0.8),
                    ),
                  ),
                ),

                const SizedBox(width: 6),

                // Kanal Logosu (Daha kompakt ve zarif)
                Container(
                  width: 52,
                  height: 36,
                  padding: const EdgeInsets.all(3),
                  decoration: BoxDecoration(
                    color: Colors.black.withValues(alpha: 0.35),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: widget.channel.logo.isNotEmpty
                      ? CachedNetworkImage(
                          imageUrl: widget.channel.logo,
                          fit: BoxFit.contain,
                          placeholder: (context, url) => const Center(
                            child: SizedBox(
                              width: 14,
                              height: 14,
                              child: CircularProgressIndicator(
                                strokeWidth: 1.5,
                                color: TVTheme.focusCyan,
                              ),
                            ),
                          ),
                          errorWidget: (context, url, error) => const Icon(
                            Icons.tv,
                            color: TVTheme.textSecondary,
                            size: 22,
                          ),
                        )
                      : const Icon(
                          Icons.tv,
                          color: TVTheme.textSecondary,
                          size: 22,
                        ),
                ),

                const SizedBox(width: 12),

                // Kanal Adı ve Kategori
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.center,
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
                            const SizedBox(width: 6),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1.5),
                              decoration: BoxDecoration(
                                color: TVTheme.liveGreen.withValues(alpha: 0.25),
                                borderRadius: BorderRadius.circular(4),
                                border: Border.all(color: TVTheme.liveGreen, width: 1),
                              ),
                              child: const Text(
                                'YAYINDA',
                                style: TextStyle(
                                  color: TVTheme.liveGreen,
                                  fontSize: 9,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 2),
                      Text(
                        widget.channel.category,
                        style: TVTheme.tvCategory,
                      ),
                    ],
                  ),
                ),

                // Favori Yıldızı
                IconButton(
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
                  icon: Icon(
                    widget.channel.isFavorite ? Icons.star_rounded : Icons.star_outline_rounded,
                    color: widget.channel.isFavorite ? TVTheme.favoriteGold : TVTheme.textSecondary,
                    size: 22,
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
