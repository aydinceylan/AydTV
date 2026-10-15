import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/tv_theme.dart';

class DefaultTvDialog extends StatefulWidget {
  const DefaultTvDialog({super.key});

  static const String _prefKey = 'has_prompted_default_tv_v2';
  static const String _dismissedKey = 'default_tv_prompt_dismissed_permanently';
  static const MethodChannel _channel = MethodChannel('com.aydtv.aydtv/system');

  static Future<void> checkAndShow(BuildContext context) async {
    final prefs = await SharedPreferences.getInstance();
    final isDismissed = prefs.getBool(_dismissedKey) ?? false;
    if (isDismissed) return;

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
  State<DefaultTvDialog> createState() => _DefaultTvDialogState();
}

class _DefaultTvDialogState extends State<DefaultTvDialog> with WidgetsBindingObserver {
  bool _isEnabled = false;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkServiceStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkServiceStatus();
    }
  }

  Future<void> _checkServiceStatus() async {
    try {
      final bool? enabled = await DefaultTvDialog._channel.invokeMethod<bool>('isAccessibilityEnabled');
      if (mounted) {
        setState(() {
          _isEnabled = enabled ?? false;
          _isLoading = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isEnabled = false;
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _openSettings() async {
    try {
      await DefaultTvDialog._channel.invokeMethod('openAccessibilitySettings');
    } catch (_) {}
  }

  Future<void> _dismissPermanently() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(DefaultTvDialog._dismissedKey, true);
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 90, vertical: 26),
      child: Container(
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: const Color(0xF20B101B),
          borderRadius: BorderRadius.circular(22),
          border: Border.all(
            color: _isEnabled ? Colors.greenAccent : TVTheme.focusCyan,
            width: 2,
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.85),
              blurRadius: 30,
              spreadRadius: 6,
            ),
          ],
        ),
        child: _isLoading
            ? const Center(
                child: Padding(
                  padding: EdgeInsets.all(32),
                  child: CircularProgressIndicator(color: TVTheme.focusCyan),
                ),
              )
            : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
            // Başlık
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: (_isEnabled ? Colors.greenAccent : TVTheme.focusCyan).withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    _isEnabled ? Icons.check_circle_rounded : Icons.live_tv_rounded,
                    color: _isEnabled ? Colors.greenAccent : TVTheme.focusCyan,
                    size: 28,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Text(
                    _isEnabled
                        ? 'TV Tuşu AydTV\'ye Başarıyla Bağlandı!'
                        : 'Kumandadaki TV Tuşunu AydTV Yapın',
                    style: const TextStyle(
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
            if (_isEnabled) ...[
              Text(
                'Harika! Kumandanızdaki Canlı TV / TV tuşuna bastığınızda televizyon doğrudan AydTV\'yi açacak şekilde yapılandırıldı.',
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.9),
                  fontSize: 14,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 10),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.greenAccent.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.greenAccent.withValues(alpha: 0.3)),
                ),
                child: const Row(
                  children: [
                    Icon(Icons.verified_user_rounded, color: Colors.greenAccent, size: 20),
                    SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'Cihazınız güvendedir. Uygulamayı kaldırırsanız kumandanız kendiliğinden orijinal haline döner.',
                        style: TextStyle(color: Colors.white70, fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ] else ...[
              Text(
                'Kumandanızdaki TV (Canlı TV) tuşuna basıldığında yerel TV yerine AydTV\'nin açılması için tek seferlik Erişilebilirlik izni gereklidir.',
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.85),
                  fontSize: 14,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 12),
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
                        Icon(Icons.touch_app_rounded, color: TVTheme.focusCyan, size: 18),
                        SizedBox(width: 8),
                        Text(
                          'Nasıl Aktif Edilir? (3 Saniye)',
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
                      '1. Aşağıdaki "Ayarları Aç" butonuna basın.\n'
                      '2. Açılan Erişilebilirlik listesinden "AydTV"yi seçin.\n'
                      '3. "Açık / Etkinleştir" konumuna getirin.',
                      style: TextStyle(
                        color: Colors.white.withValues(alpha: 0.9),
                        fontSize: 13,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 20),

            // Butonlar
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  onPressed: () {
                    _dismissPermanently();
                    Navigator.of(context).pop();
                  },
                  child: Text(
                    _isEnabled ? 'Kapat' : 'Daha Sonra',
                    style: const TextStyle(color: TVTheme.textSecondary, fontSize: 14),
                  ),
                ),
                const SizedBox(width: 12),
                if (!_isEnabled)
                  ElevatedButton(
                    autofocus: true,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: TVTheme.focusCyan,
                      foregroundColor: Colors.black,
                      padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 12),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    ),
                    onPressed: () {
                      _dismissPermanently();
                      _openSettings();
                    },
                    child: const Text(
                      'Ayarları Aç (Erişilebilirlik)',
                      style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                    ),
                  )
                else
                  ElevatedButton(
                    autofocus: true,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.greenAccent,
                      foregroundColor: Colors.black,
                      padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 12),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    ),
                    onPressed: () {
                      _dismissPermanently();
                      Navigator.of(context).pop();
                    },
                    child: const Text(
                      'Tamam, Harika',
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
