import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/tv_theme.dart';

class DefaultTvDialog extends StatelessWidget {
  const DefaultTvDialog({super.key});

  static const String _prefKey = 'has_prompted_default_tv_v1';

  static Future<void> checkAndShow(BuildContext context) async {
    final prefs = await SharedPreferences.getInstance();
    final hasPrompted = prefs.getBool(_prefKey) ?? false;
    if (!hasPrompted) {
      await prefs.setBool(_prefKey, true);
      if (context.mounted) {
        show(context);
      }
    }
  }

  static void show(BuildContext context) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => const DefaultTvDialog(),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 100, vertical: 30),
      child: Container(
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: const Color(0xF20B101B),
          borderRadius: BorderRadius.circular(22),
          border: Border.all(color: TVTheme.focusCyan, width: 2),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.8),
              blurRadius: 30,
              spreadRadius: 6,
            ),
          ],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Başlık
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: TVTheme.focusCyan.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(Icons.live_tv_rounded, color: TVTheme.focusCyan, size: 28),
                ),
                const SizedBox(width: 14),
                const Expanded(
                  child: Text(
                    'AydTV\'yi Televizyonun Ana TV\'si Yapın',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 19,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),

            // Açıklama
            Text(
              'Kumandanızdaki TV tuşuna basıldığında ve televizyon açıldığında yerel uydu yerine doğrudan AydTV\'nin açılmasını sağlayabilirsiniz.',
              style: TextStyle(
                color: Colors.white.withValues(alpha: 0.85),
                fontSize: 14,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 14),

            // Nasıl Yapılır Kutusu
            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.05),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.3)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: const [
                      Icon(Icons.info_outline_rounded, color: TVTheme.focusCyan, size: 18),
                      SizedBox(width: 8),
                      Text(
                        'Nasıl Yapılır? (Tek Seferlik)',
                        style: TextStyle(
                          color: TVTheme.focusCyan,
                          fontWeight: FontWeight.bold,
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '1. Kumandanızdaki TV (Canlı TV) tuşuna basın.\n'
                    '2. Ekrana gelen listeden "AydTV"yi seçin.\n'
                    '3. "HER ZAMAN (Always)" seçeneğine tıklayın.',
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.9),
                      fontSize: 13,
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),

            // Butonlar
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text(
                    'Daha Sonra',
                    style: TextStyle(color: TVTheme.textSecondary, fontSize: 14),
                  ),
                ),
                const SizedBox(width: 10),
                ElevatedButton(
                  autofocus: true,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: TVTheme.focusCyan,
                    foregroundColor: Colors.black,
                    padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 12),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  ),
                  onPressed: () {
                    Navigator.of(context).pop();
                    // Live TV intent'ini tetikleyerek sistem seçimini ekrana çağır
                    const channel = MethodChannel('com.aydtv.aydtv/system');
                    channel.invokeMethod('requestDefaultTv');
                  },
                  child: const Text(
                    'Tamam, Anladım',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
