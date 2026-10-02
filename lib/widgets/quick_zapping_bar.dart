import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../models/channel.dart';
import '../theme/tv_theme.dart';

class QuickZappingBar extends StatelessWidget {
  final Channel channel;
  final int channelIndex;
  final int totalChannels;

  const QuickZappingBar({
    super.key,
    required this.channel,
    required this.channelIndex,
    required this.totalChannels,
  });

  @override
  Widget build(BuildContext context) {
    return Positioned(
      bottom: 36,
      left: 60,
      right: 60,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(20),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
            decoration: BoxDecoration(
              color: const Color(0xCC090C10),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color: TVTheme.focusCyan.withValues(alpha: 0.5),
                width: 1.5,
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.6),
                  blurRadius: 24,
                  offset: const Offset(0, 8),
                ),
              ],
            ),
            child: Row(
              children: [
                // Kanal No
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      colors: [TVTheme.focusCyan, TVTheme.focusBlue],
                    ),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    '${channelIndex + 1}',
                    style: const TextStyle(
                      color: Colors.black,
                      fontWeight: FontWeight.w900,
                      fontSize: 22,
                    ),
                  ),
                ),

                const SizedBox(width: 18),

                // Kanal Logosu
                if (channel.logo.isNotEmpty)
                  Container(
                    width: 64,
                    height: 44,
                    padding: const EdgeInsets.all(4),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.4),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: CachedNetworkImage(
                      imageUrl: channel.logo,
                      fit: BoxFit.contain,
                      errorWidget: (c, u, e) => const Icon(Icons.tv, color: Colors.white54),
                    ),
                  ),

                const SizedBox(width: 18),

                // Kanal Adı ve Kategori
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        channel.name,
                        style: TVTheme.tvTitle.copyWith(fontSize: 24),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      const SizedBox(height: 2),
                      Row(
                        children: [
                          Text(
                            channel.category,
                            style: TVTheme.tvCategory.copyWith(color: TVTheme.focusCyan),
                          ),
                          const SizedBox(width: 12),
                          const CircleAvatar(radius: 3, backgroundColor: Colors.white38),
                          const SizedBox(width: 12),
                          Text(
                            '$totalChannels Kanal Arasından',
                            style: TVTheme.tvCategory,
                          ),
                        ],
                      ),
                    ],
                  ),
                ),

                // Zapping İpuçları
                const Row(
                  children: [
                    Icon(Icons.arrow_left, color: TVTheme.focusCyan, size: 28),
                    Text('Önceki', style: TextStyle(color: Colors.white70, fontSize: 14)),
                    SizedBox(width: 16),
                    Text('Sonraki', style: TextStyle(color: Colors.white70, fontSize: 14)),
                    Icon(Icons.arrow_right, color: TVTheme.focusCyan, size: 28),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
