import 'package:flutter/material.dart';
import '../services/cast_service.dart';
import '../theme/tv_theme.dart';

class CastMusicView extends StatelessWidget {
  final CastMediaInfo media;
  final String? deviceName;
  final bool isPlaying;
  final VoidCallback onTogglePlay;
  final VoidCallback onSeekForward;
  final VoidCallback onSeekBackward;
  final VoidCallback onDisconnect;

  const CastMusicView({
    super.key,
    required this.media,
    required this.deviceName,
    required this.isPlaying,
    required this.onTogglePlay,
    required this.onSeekForward,
    required this.onSeekBackward,
    required this.onDisconnect,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      height: double.infinity,
      decoration: BoxDecoration(
        gradient: RadialGradient(
          center: Alignment.center,
          radius: 1.1,
          colors: [
            const Color(0xFF142036),
            const Color(0xFF080C14),
            Colors.black,
          ],
        ),
      ),
      child: Stack(
        children: [
          // Arka plan neon parlaması
          Center(
            child: Container(
              width: 320,
              height: 320,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                boxShadow: [
                  BoxShadow(
                    color: TVTheme.focusCyan.withValues(alpha: 0.15),
                    blurRadius: 100,
                    spreadRadius: 40,
                  ),
                ],
              ),
            ),
          ),

          // İçerik
          Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Albüm Kapağı / İkon Kartı
                Container(
                  width: 220,
                  height: 220,
                  decoration: BoxDecoration(
                    color: const Color(0xFF1B2438),
                    borderRadius: BorderRadius.circular(28),
                    border: Border.all(
                      color: isPlaying ? TVTheme.focusCyan : Colors.white24,
                      width: 2.5,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.8),
                        blurRadius: 35,
                        spreadRadius: 8,
                      ),
                    ],
                  ),
                  child: Center(
                    child: Icon(
                      Icons.music_note_rounded,
                      size: 96,
                      color: isPlaying ? TVTheme.focusCyan : Colors.white54,
                    ),
                  ),
                ),
                const SizedBox(height: 28),

                // Şarkı Adı
                Text(
                  media.title.isNotEmpty ? media.title : 'Ses / Müzik Akışı',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 26,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 0.5,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 8),

                // Sanatçı & Cihaz
                Text(
                  '${media.artist.isNotEmpty ? media.artist : "Apple / Android Cihazı"} • ${deviceName ?? "AydTV"}',
                  style: TextStyle(
                    color: TVTheme.focusCyan.withValues(alpha: 0.85),
                    fontSize: 16,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 36),

                // Kumanda Kısayolları Çubuğu (Glass Bar)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.06),
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: Colors.white.withValues(alpha: 0.1)),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _buildKeyHint('OK', isPlaying ? 'Duraklat' : 'Oynat'),
                      const SizedBox(width: 20),
                      _buildKeyHint('◀◀', '10 sn Geri'),
                      const SizedBox(width: 20),
                      _buildKeyHint('▶▶', '10 sn İleri'),
                      const SizedBox(width: 20),
                      _buildKeyHint('GERİ', 'TV Yayınına Dön'),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildKeyHint(String keyLabel, String actionLabel) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          decoration: BoxDecoration(
            color: TVTheme.focusCyan.withValues(alpha: 0.2),
            borderRadius: BorderRadius.circular(6),
            border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.4)),
          ),
          child: Text(
            keyLabel,
            style: const TextStyle(
              color: TVTheme.focusCyan,
              fontSize: 12,
              fontWeight: FontWeight.bold,
            ),
          ),
        ),
        const SizedBox(width: 8),
        Text(
          actionLabel,
          style: const TextStyle(color: Colors.white70, fontSize: 13),
        ),
      ],
    );
  }
}
