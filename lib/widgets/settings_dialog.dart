import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/tv_theme.dart';
import '../services/update_service.dart';

class SettingsDialog extends StatefulWidget {
  final VoidCallback? onCheckUpdate;
  final VoidCallback? onSettingsChanged;

  const SettingsDialog({super.key, this.onCheckUpdate, this.onSettingsChanged});

  static Future<void> show(BuildContext context, {VoidCallback? onCheckUpdate, VoidCallback? onSettingsChanged}) {
    return showDialog(
      context: context,
      barrierDismissible: true,
      builder: (context) => SettingsDialog(
        onCheckUpdate: onCheckUpdate,
        onSettingsChanged: onSettingsChanged,
      ),
    );
  }

  @override
  State<SettingsDialog> createState() => _SettingsDialogState();
}

class _SettingsDialogState extends State<SettingsDialog> {
  bool _autoStartOnBoot = true;
  bool _keepScreenOn = true;
  bool _muteOnBackground = true;
  String _bufferMode = 'balanced'; // 'ultra', 'balanced', 'shield'
  bool _isLoading = true;

  final FocusNode _closeFocusNode = FocusNode();
  final FocusNode _bufferFocusNode = FocusNode();
  final FocusNode _bootFocusNode = FocusNode();
  final FocusNode _screenFocusNode = FocusNode();
  final FocusNode _bgAudioFocusNode = FocusNode();
  final FocusNode _updateFocusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  @override
  void dispose() {
    _closeFocusNode.dispose();
    _bufferFocusNode.dispose();
    _bootFocusNode.dispose();
    _screenFocusNode.dispose();
    _bgAudioFocusNode.dispose();
    _updateFocusNode.dispose();
    super.dispose();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _autoStartOnBoot = prefs.getBool('auto_start_on_boot') ?? true;
      _keepScreenOn = prefs.getBool('keep_screen_on') ?? true;
      _muteOnBackground = prefs.getBool('mute_on_background') ?? true;
      _bufferMode = prefs.getString('buffer_mode') ?? 'balanced';
      _isLoading = false;
    });
  }

  Future<void> _saveSetting(String key, dynamic value) async {
    final prefs = await SharedPreferences.getInstance();
    if (value is bool) {
      await prefs.setBool(key, value);
    } else if (value is String) {
      await prefs.setString(key, value);
    }
    widget.onSettingsChanged?.call();
  }

  void _cycleBufferMode() {
    String nextMode;
    if (_bufferMode == 'balanced') {
      nextMode = 'shield';
    } else if (_bufferMode == 'shield') {
      nextMode = 'ultra';
    } else {
      nextMode = 'balanced';
    }
    setState(() {
      _bufferMode = nextMode;
    });
    _saveSetting('buffer_mode', nextMode);
  }

  String get _bufferModeTitle {
    switch (_bufferMode) {
      case 'ultra':
        return '⚡ Ultra Canlı (8 sn Tampon - Minimum Gecikme)';
      case 'shield':
        return '🏰 Zırhlı Mod (25 sn Tampon - Maksimum Kararlılık)';
      case 'balanced':
      default:
        return '🛡️ Kesintisiz & Dengeli (16 sn Tampon - Önerilen)';
    }
  }

  String get _bufferModeSubtitle {
    switch (_bufferMode) {
      case 'ultra':
        return 'Maç ve canlı skorlar için minimum gecikme sağlar (hızlı internet önerilir).';
      case 'shield':
        return 'Wi-Fi dalgalanmalarında veya yavaş internette donmayı tamamen sıfırlar.';
      case 'balanced':
      default:
        return 'Yayın takılmalarını ve çark beklemesini önleyen ideal canlı yayın güvenliği.';
    }
  }

  Widget _buildBufferModeTile() {
    return Focus(
      focusNode: _bufferFocusNode,
      onKeyEvent: (node, event) {
        if (event.runtimeType.toString().contains('KeyDownEvent')) {
          final keyName = event.logicalKey.debugName ?? '';
          if (keyName.contains('Enter') || keyName.contains('Select')) {
            _cycleBufferMode();
            return KeyEventResult.handled;
          }
        }
        return KeyEventResult.ignored;
      },
      child: Builder(
        builder: (context) {
          final isFocused = Focus.of(context).hasFocus;
          return InkWell(
            onTap: _cycleBufferMode,
            child: Container(
              margin: const EdgeInsets.symmetric(vertical: 4),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                color: isFocused
                    ? TVTheme.focusCyan.withValues(alpha: 0.20)
                    : Colors.white.withValues(alpha: 0.05),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: isFocused ? TVTheme.focusCyan : TVTheme.focusCyan.withValues(alpha: 0.3),
                  width: isFocused ? 2 : 1.2,
                ),
              ),
              child: Row(
                children: [
                  const Icon(
                    Icons.speed_rounded,
                    color: TVTheme.focusCyan,
                    size: 26,
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Text(
                              'Yayın Kararlılık & Tampon Modu: ',
                              style: TextStyle(
                                color: Colors.white70,
                                fontSize: 13,
                              ),
                            ),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                              decoration: BoxDecoration(
                                color: TVTheme.focusCyan.withValues(alpha: 0.25),
                                borderRadius: BorderRadius.circular(6),
                              ),
                              child: const Text(
                                'Değiştirmek İçin OK',
                                style: TextStyle(
                                  color: TVTheme.focusCyan,
                                  fontSize: 10,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _bufferModeTitle,
                          style: TextStyle(
                            color: isFocused ? Colors.white : TVTheme.focusCyan,
                            fontWeight: FontWeight.bold,
                            fontSize: 15,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          _bufferModeSubtitle,
                          style: TextStyle(
                            color: TVTheme.textSecondary.withValues(alpha: 0.85),
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const Icon(Icons.arrow_forward_ios_rounded, color: TVTheme.focusCyan, size: 16),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildSettingTile({
    required FocusNode focusNode,
    required IconData icon,
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Focus(
      focusNode: focusNode,
      onKeyEvent: (node, event) {
        if (event.runtimeType.toString().contains('KeyDownEvent')) {
          final keyName = event.logicalKey.debugName ?? '';
          if (keyName.contains('Enter') || keyName.contains('Select')) {
            onChanged(!value);
            return KeyEventResult.handled;
          }
        }
        return KeyEventResult.ignored;
      },
      child: Builder(
        builder: (context) {
          final isFocused = Focus.of(context).hasFocus;
          return Container(
            margin: const EdgeInsets.symmetric(vertical: 4),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(
              color: isFocused
                  ? TVTheme.focusCyan.withValues(alpha: 0.15)
                  : Colors.white.withValues(alpha: 0.04),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: isFocused ? TVTheme.focusCyan : Colors.white.withValues(alpha: 0.08),
                width: isFocused ? 1.5 : 1,
              ),
            ),
            child: Row(
              children: [
                Icon(
                  icon,
                  color: isFocused ? TVTheme.focusCyan : Colors.white70,
                  size: 24,
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: TextStyle(
                          color: isFocused ? Colors.white : Colors.white.withValues(alpha: 0.9),
                          fontWeight: FontWeight.w600,
                          fontSize: 15,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        subtitle,
                        style: TextStyle(
                          color: TVTheme.textSecondary.withValues(alpha: 0.8),
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                Switch(
                  value: value,
                  activeThumbColor: TVTheme.focusCyan,
                  activeTrackColor: TVTheme.focusCyan.withValues(alpha: 0.5),
                  onChanged: (newVal) => onChanged(newVal),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 110, vertical: 30),
      child: Container(
        padding: const EdgeInsets.all(22),
        decoration: BoxDecoration(
          color: const Color(0xF20B101B),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.4), width: 1.5),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.75),
              blurRadius: 25,
              spreadRadius: 5,
            ),
          ],
        ),
        child: _isLoading
            ? const Center(child: CircularProgressIndicator(color: TVTheme.focusCyan))
            : SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Başlık
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Row(
                          children: [
                            Container(
                              padding: const EdgeInsets.all(8),
                              decoration: BoxDecoration(
                                color: TVTheme.focusCyan.withValues(alpha: 0.15),
                                borderRadius: BorderRadius.circular(10),
                              ),
                              child: const Icon(Icons.settings_suggest_rounded, color: TVTheme.focusCyan, size: 26),
                            ),
                            const SizedBox(width: 12),
                            const Text(
                              'AydTV Ayarları',
                              style: TextStyle(
                                color: Colors.white,
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ],
                        ),
                        Focus(
                          focusNode: _closeFocusNode,
                          child: Builder(
                            builder: (context) {
                              final isFocused = Focus.of(context).hasFocus;
                              return IconButton(
                                icon: Icon(
                                  Icons.close_rounded,
                                  color: isFocused ? TVTheme.focusCyan : Colors.white54,
                                  size: 26,
                                ),
                                onPressed: () => Navigator.of(context).pop(),
                              );
                            },
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 14),

                    // 1. Yayın Kararlılık & Tampon Modu
                    _buildBufferModeTile(),

                    // 2. TV Açıldığında Otomatik Başlat
                    _buildSettingTile(
                      focusNode: _bootFocusNode,
                      icon: Icons.power_settings_new_rounded,
                      title: 'TV Açıldığında Otomatik Başlat',
                      subtitle: 'Televizyon veya TV Box açıldığında doğrudan AydTV yayını başlasın.',
                      value: _autoStartOnBoot,
                      onChanged: (val) {
                        setState(() => _autoStartOnBoot = val);
                        _saveSetting('auto_start_on_boot', val);
                      },
                    ),

                    // 3. Ekranı Sürekli Açık Tut / Uyku Modunu Engelle
                    _buildSettingTile(
                      focusNode: _screenFocusNode,
                      icon: Icons.tv_rounded,
                      title: 'Ekranı Sürekli Açık Tut (Uyku Engelle)',
                      subtitle: 'Yayın açıkken televizyonun hareketsizlikten kapanmasını önler.',
                      value: _keepScreenOn,
                      onChanged: (val) {
                        setState(() => _keepScreenOn = val);
                        _saveSetting('keep_screen_on', val);
                      },
                    ),

                    // 4. Arka Plana Geçince Sesi Durdur
                    _buildSettingTile(
                      focusNode: _bgAudioFocusNode,
                      icon: Icons.volume_off_rounded,
                      title: 'Arka Planda Sesi Durdur',
                      subtitle: 'TV ana menüsüne veya başka uygulamaya geçince yayının sesi kesilsin.',
                      value: _muteOnBackground,
                      onChanged: (val) {
                        setState(() => _muteOnBackground = val);
                        _saveSetting('mute_on_background', val);
                      },
                    ),

                    const SizedBox(height: 12),

                    // Güncelleme Kontrolü Butonu
                    Focus(
                      focusNode: _updateFocusNode,
                      onKeyEvent: (node, event) {
                        if (event.runtimeType.toString().contains('KeyDownEvent')) {
                          final keyName = event.logicalKey.debugName ?? '';
                          if (keyName.contains('Enter') || keyName.contains('Select')) {
                            Navigator.of(context).pop();
                            widget.onCheckUpdate?.call();
                            return KeyEventResult.handled;
                          }
                        }
                        return KeyEventResult.ignored;
                      },
                      child: Builder(
                        builder: (context) {
                          final isFocused = Focus.of(context).hasFocus;
                          return InkWell(
                            onTap: () {
                              Navigator.of(context).pop();
                              widget.onCheckUpdate?.call();
                            },
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                              decoration: BoxDecoration(
                                color: isFocused ? TVTheme.focusCyan : Colors.white12,
                                borderRadius: BorderRadius.circular(12),
                                border: Border.all(
                                  color: isFocused ? Colors.white : Colors.white24,
                                ),
                              ),
                              child: Row(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  Icon(
                                    Icons.system_update_rounded,
                                    color: isFocused ? Colors.black : TVTheme.focusCyan,
                                    size: 20,
                                  ),
                                  const SizedBox(width: 10),
                                  Text(
                                    'Güncellemeleri Şimdi Denetle',
                                    style: TextStyle(
                                      color: isFocused ? Colors.black : Colors.white,
                                      fontWeight: FontWeight.bold,
                                      fontSize: 14,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          );
                        },
                      ),
                    ),

                    const SizedBox(height: 10),

                    // Sürüm ve Telif Bilgisi
                    Center(
                      child: Text(
                        'AydTV v${UpdateService.currentVersion} • aydcode.com.tr',
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.4),
                          fontSize: 12,
                          letterSpacing: 0.5,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
      ),
    );
  }
}
