import 'package:flutter/material.dart';

class TVTheme {
  // Sinematik Renk Paleti (55" TV için yüksek kontrast ve OLED uyumlu)
  static const Color background = Color(0xFF090C10);
  static const Color surface = Color(0xFF161B22);
  static const Color surfaceGlass = Color(0xCC111620);
  static const Color focusCyan = Color(0xFF00F2FE);
  static const Color focusBlue = Color(0xFF4FACFE);
  static const Color favoriteGold = Color(0xFFFFC107);
  static const Color textPrimary = Color(0xFFF0F6FC);
  static const Color textSecondary = Color(0xFF8B949E);
  static const Color liveGreen = Color(0xFF00E676);

  // Kumanda Odağı (Neon Focus Glow) Dekoru
  static BoxDecoration focusDecoration({bool isFocused = false, bool isPlaying = false}) {
    if (isFocused) {
      return BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xFF1A2639), Color(0xFF111D2E)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: focusCyan,
          width: 3.0,
        ),
        boxShadow: [
          BoxShadow(
            color: focusCyan.withValues(alpha: 0.45),
            blurRadius: 18,
            spreadRadius: 2,
          ),
        ],
      );
    }

    return BoxDecoration(
      color: isPlaying ? const Color(0xFF1C2430) : const Color(0xFF161B22).withValues(alpha: 0.8),
      borderRadius: BorderRadius.circular(16),
      border: Border.all(
        color: isPlaying ? focusBlue.withValues(alpha: 0.6) : Colors.white.withValues(alpha: 0.08),
        width: isPlaying ? 2.0 : 1.0,
      ),
    );
  }

  // 55" TV için dengeli ve rafine tipografi
  static const TextStyle tvTitle = TextStyle(
    fontSize: 22,
    fontWeight: FontWeight.bold,
    color: textPrimary,
    letterSpacing: 0.5,
  );

  static const TextStyle tvChannelName = TextStyle(
    fontSize: 17,
    fontWeight: FontWeight.w600,
    color: textPrimary,
  );

  static const TextStyle tvCategory = TextStyle(
    fontSize: 13,
    fontWeight: FontWeight.w500,
    color: textSecondary,
  );

  static const TextStyle tvBadge = TextStyle(
    fontSize: 12,
    fontWeight: FontWeight.bold,
    color: Colors.white,
    letterSpacing: 0.5,
  );
}
