import 'package:flutter/material.dart';
import '../theme/tv_theme.dart';

class CastDisconnectDialog extends StatelessWidget {
  final String? deviceName;
  final VoidCallback onConfirmDisconnect;

  const CastDisconnectDialog({
    super.key,
    required this.deviceName,
    required this.onConfirmDisconnect,
  });

  static Future<bool?> show(BuildContext context, {String? deviceName, required VoidCallback onConfirm}) {
    return showDialog<bool>(
      context: context,
      barrierDismissible: true,
      builder: (context) => CastDisconnectDialog(
        deviceName: deviceName,
        onConfirmDisconnect: onConfirm,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 110, vertical: 30),
      child: Container(
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: const Color(0xF20B101B),
          borderRadius: BorderRadius.circular(22),
          border: Border.all(color: TVTheme.focusCyan, width: 2),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.85),
              blurRadius: 30,
              spreadRadius: 6,
            ),
          ],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.redAccent.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(Icons.cast_connected_rounded, color: Colors.redAccent, size: 28),
                ),
                const SizedBox(width: 14),
                const Expanded(
                  child: Text(
                    'Ekran Paylaşımını Sonlandır?',
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
            Text(
              '${deviceName != null ? "\"$deviceName\" cihazından yapılan" : "Mevcut"} ekran paylaşımını sonlandırıp canlı TV yayınına geri dönmek istiyor musunuz?',
              style: TextStyle(
                color: Colors.white.withValues(alpha: 0.85),
                fontSize: 14,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 24),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: const Text(
                    'Vazgeç (Devam Et)',
                    style: TextStyle(color: TVTheme.textSecondary, fontSize: 14),
                  ),
                ),
                const SizedBox(width: 14),
                ElevatedButton(
                  autofocus: true,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.redAccent,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  ),
                  onPressed: () {
                    Navigator.of(context).pop(true);
                    onConfirmDisconnect();
                  },
                  child: const Text(
                    'Evet, TV Yayınına Dön',
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
