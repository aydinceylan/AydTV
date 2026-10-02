import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../models/channel.dart';
import '../theme/tv_theme.dart';

class ChannelCard extends StatefulWidget {
  final Channel channel;
  final int index;
  final bool isPlaying;
  final VoidCallback onSelect;
  final VoidCallback onToggleFavorite;

  const ChannelCard({
    super.key,
    required this.channel,
    required this.index,
    required this.isPlaying,
    required this.onSelect,
    required this.onToggleFavorite,
  });

  @override
  State<ChannelCard> createState() => _ChannelCardState();
}

class _ChannelCardState extends State<ChannelCard> {
  bool _isFocused = false;

  @override
  Widget build(BuildContext context) {
    return Focus(
      onFocusChange: (focused) {
        setState(() {
          _isFocused = focused;
        });
      },
      child: GestureDetector(
        onTap: widget.onSelect,
        child: AnimatedScale(
          scale: _isFocused ? 1.05 : 1.0,
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            margin: const EdgeInsets.symmetric(vertical: 6, horizontal: 8),
            padding: const EdgeInsets.all(12),
            decoration: TVTheme.focusDecoration(
              isFocused: _isFocused,
              isPlaying: widget.isPlaying,
            ),
            child: Row(
              children: [
                // Kanal Numarası
                Container(
                  width: 44,
                  alignment: Alignment.center,
                  child: Text(
                    '${widget.index + 1}',
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                      color: _isFocused
                          ? TVTheme.focusCyan
                          : TVTheme.textSecondary.withValues(alpha: 0.8),
                    ),
                  ),
                ),

                const SizedBox(width: 8),

                // Kanal Logosu
                Container(
                  width: 68,
                  height: 48,
                  padding: const EdgeInsets.all(4),
                  decoration: BoxDecoration(
                    color: Colors.black.withValues(alpha: 0.3),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: widget.channel.logo.isNotEmpty
                      ? CachedNetworkImage(
                          imageUrl: widget.channel.logo,
                          fit: BoxFit.contain,
                          placeholder: (context, url) => const Center(
                            child: SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: TVTheme.focusCyan,
                              ),
                            ),
                          ),
                          errorWidget: (context, url, error) => const Icon(
                            Icons.tv,
                            color: TVTheme.textSecondary,
                            size: 28,
                          ),
                        )
                      : const Icon(
                          Icons.tv,
                          color: TVTheme.textSecondary,
                          size: 28,
                        ),
                ),

                const SizedBox(width: 16),

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
                            const SizedBox(width: 8),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                              decoration: BoxDecoration(
                                color: TVTheme.liveGreen.withValues(alpha: 0.2),
                                borderRadius: BorderRadius.circular(6),
                                border: Border.all(color: TVTheme.liveGreen, width: 1),
                              ),
                              child: const Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  CircleAvatar(
                                    radius: 3,
                                    backgroundColor: TVTheme.liveGreen,
                                  ),
                                  SizedBox(width: 4),
                                  Text(
                                    'YAYINDA',
                                    style: TextStyle(
                                      color: TVTheme.liveGreen,
                                      fontSize: 10,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        widget.channel.category,
                        style: TVTheme.tvCategory,
                      ),
                    ],
                  ),
                ),

                // Favori Butonu
                IconButton(
                  icon: Icon(
                    widget.channel.isFavorite ? Icons.star_rounded : Icons.star_outline_rounded,
                    color: widget.channel.isFavorite ? TVTheme.favoriteGold : TVTheme.textSecondary,
                    size: 28,
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
