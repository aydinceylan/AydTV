import 'package:flutter/material.dart';

class TVTheme {
  // Sinematik Liquid Glass Renk Paleti (55" TV ve OLED uyumlu)
  static const Color background = Color(0xFF07090E);
  static const Color surface = Color(0xFF111620);
  
  // Şeffaf Sıvı Cam (Liquid Glass) Katmanları
  static const Color liquidGlassBg = Color(0x990A0E17);
  static const Color liquidCardBg = Color(0x1F2A384C);
  
  static const Color focusCyan = Color(0xFF00F2FE);
  static const Color focusBlue = Color(0xFF4FACFE);
  static const Color favoriteGold = Color(0xFFFFC107);
  static const Color textPrimary = Color(0xFFF0F6FC);
  static const Color textSecondary = Color(0xFF8B949E);
  static const Color liveGreen = Color(0xFF00E676);

  // Kumanda Odağı (Liquid Glass Focus Glow) Dekoru
  static BoxDecoration focusDecoration({bool isFocused = false, bool isPlaying = false}) {
    if (isFocused) {
      return BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0x4D00F2FE), Color(0x264FACFE)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: focusCyan,
          width: 2.2,
        ),
        boxShadow: [
          BoxShadow(
            color: focusCyan.withValues(alpha: 0.35),
            blurRadius: 12,
            spreadRadius: 1,
          ),
        ],
      );
    }

    return BoxDecoration(
      color: isPlaying ? const Color(0x334FACFE) : liquidCardBg,
      borderRadius: BorderRadius.circular(12),
      border: Border.all(
        color: isPlaying
            ? focusBlue.withValues(alpha: 0.6)
            : Colors.white.withValues(alpha: 0.08),
        width: 1.0,
      ),
    );
  }

  // 55" TV Rafine Tipografi
  static const TextStyle tvTitle = TextStyle(
    fontSize: 18,
    fontWeight: FontWeight.bold,
    color: textPrimary,
    letterSpacing: 0.5,
  );

  static const TextStyle tvChannelName = TextStyle(
    fontSize: 15,
    fontWeight: FontWeight.w600,
    color: textPrimary,
  );

  static const TextStyle tvEpg = TextStyle(
    fontSize: 11,
    fontWeight: FontWeight.w400,
    color: Color(0xFF90A4AE),
  );

  static const TextStyle tvCategory = TextStyle(
    fontSize: 12,
    fontWeight: FontWeight.w500,
    color: textSecondary,
  );

  static const TextStyle tvBadge = TextStyle(
    fontSize: 10,
    fontWeight: FontWeight.bold,
    color: Colors.white,
    letterSpacing: 0.5,
  );
}
