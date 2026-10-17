import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/channel.dart';
import '../services/cast_service.dart';
import '../services/channel_service.dart';
import '../services/sleep_timer_service.dart';
import '../services/update_service.dart';
import '../theme/tv_theme.dart';
import 'default_tv_dialog.dart';

class SettingsDialog extends StatefulWidget {
  final VoidCallback? onCheckUpdate;
  final VoidCallback? onSettingsChanged;
  final List<Channel>? channels;
  final Function(List<Channel>)? onChannelsReordered;

  const SettingsDialog({
    super.key,
    this.onCheckUpdate,
    this.onSettingsChanged,
    this.channels,
    this.onChannelsReordered,
  });

  static Future<void> show(
    BuildContext context, {
    VoidCallback? onCheckUpdate,
    VoidCallback? onSettingsChanged,
    List<Channel>? channels,
    Function(List<Channel>)? onChannelsReordered,
  }) {
    return showDialog(
      context: context,
      barrierDismissible: true,
      builder: (context) => SettingsDialog(
        onCheckUpdate: onCheckUpdate,
        onSettingsChanged: onSettingsChanged,
        channels: channels,
        onChannelsReordered: onChannelsReordered,
      ),
    );
  }

  static final ValueNotifier<List<Channel>?> globalChannelOrderNotifier = ValueNotifier<List<Channel>?>(null);

  @override
  State<SettingsDialog> createState() => _SettingsDialogState();
}

class _SettingsDialogState extends State<SettingsDialog> {
  int _selectedTab = 0; // 0: Genel, 1: Kanallar, 2: Kumanda/AirPlay, 3: Uyku, 4: Sistem
  bool _isLoading = true;

  // Ayar Değerleri
  bool _autoStartOnBoot = true;
  bool _keepScreenOn = true;
  bool _muteOnBackground = true;
  bool _castEnabled = true;
  bool _castPinEnabled = false;
  String _bufferMode = 'balanced';
  String _localIp = '127.0.0.1';

  List<Channel> _orderableChannels = [];
  int? _movingChannelIndex;

  final ChannelService _channelService = ChannelService();
  final SleepTimerService _sleepService = SleepTimerService();

  @override
  void initState() {
    super.initState();
    _loadSettings();
    _sleepService.onTick = () {
      if (mounted) setState(() {});
    };
    SettingsDialog.globalChannelOrderNotifier.addListener(_onGlobalOrderChanged);
  }

  @override
  void dispose() {
    SettingsDialog.globalChannelOrderNotifier.removeListener(_onGlobalOrderChanged);
    super.dispose();
  }

  void _onGlobalOrderChanged() {
    if (mounted && SettingsDialog.globalChannelOrderNotifier.value != null) {
      setState(() {
        _orderableChannels = List.from(SettingsDialog.globalChannelOrderNotifier.value!);
        _movingChannelIndex = null;
      });
    }
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    final ip = await CastService().getLocalIp();

    List<Channel> chs = [];
    if (widget.channels != null && widget.channels!.isNotEmpty) {
      chs = List.from(widget.channels!);
    } else {
      chs = await _channelService.loadInitialChannels();
    }

    setState(() {
      _autoStartOnBoot = prefs.getBool('auto_start_on_boot') ?? true;
      _keepScreenOn = prefs.getBool('keep_screen_on') ?? true;
      _muteOnBackground = prefs.getBool('mute_on_background') ?? true;
      _castEnabled = prefs.getBool('cast_enabled') ?? true;
      _castPinEnabled = prefs.getBool('cast_pin_enabled') ?? false;
      _bufferMode = prefs.getString('buffer_mode') ?? 'balanced';
      _localIp = ip;
      _orderableChannels = chs;
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

    if (key == 'cast_enabled') {
      if (value == true) {
        CastService().startServer(pinEnabled: _castPinEnabled);
      } else {
        CastService().stopServer();
      }
    } else if (key == 'cast_pin_enabled') {
      if (_castEnabled) {
        CastService().startServer(pinEnabled: value == true);
      }
    }

    widget.onSettingsChanged?.call();
  }

  void _cycleBufferMode() {
    String nextMode = _bufferMode == 'balanced'
        ? 'shield'
        : (_bufferMode == 'shield' ? 'ultra' : 'balanced');
    setState(() => _bufferMode = nextMode);
    _saveSetting('buffer_mode', nextMode);
  }

  void _moveChannel(int oldIndex, int newIndex) {
    if (newIndex < 0 || newIndex >= _orderableChannels.length) return;
    setState(() {
      final ch = _orderableChannels.removeAt(oldIndex);
      _orderableChannels.insert(newIndex, ch);
      _movingChannelIndex = newIndex;
    });
    _channelService.saveChannelOrder(_orderableChannels);
    widget.onChannelsReordered?.call(_orderableChannels);
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 50, vertical: 24),
      child: Container(
        width: 1050,
        height: 580,
        decoration: BoxDecoration(
          color: const Color(0xF5080E1A),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.5), width: 1.5),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.85),
              blurRadius: 35,
              spreadRadius: 8,
            ),
          ],
        ),
        child: _isLoading
            ? const Center(child: CircularProgressIndicator(color: TVTheme.focusCyan))
            : Row(
                children: [
                  // SOL PANEL: KATEGORİ SEKMELERİ
                  Container(
                    width: 250,
                    padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 12),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.4),
                      borderRadius: const BorderRadius.horizontal(left: Radius.circular(23)),
                      border: Border(right: BorderSide(color: Colors.white.withValues(alpha: 0.08))),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Başlık
                        Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                          child: Row(
                            children: const [
                              Icon(Icons.tune_rounded, color: TVTheme.focusCyan, size: 24),
                              SizedBox(width: 10),
                              Text(
                                'AydTV Ayarları',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 18,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 14),

                        // Sekmeler
                        _buildTabButton(0, Icons.speed_rounded, 'Genel & Yayın'),
                        _buildTabButton(1, Icons.format_list_numbered_rounded, 'Kanal Sıralama'),
                        _buildTabButton(2, Icons.airplay_rounded, 'Kumanda & AirPlay'),
                        _buildTabButton(3, Icons.bedtime_rounded, 'Uyku Zamanlayıcı'),
                        _buildTabButton(4, Icons.settings_system_daydream_rounded, 'Sistem & TV Tuşu'),

                        const Spacer(),

                        Focus(
                          onKeyEvent: (node, event) {
                            if (event is KeyDownEvent) {
                              final key = event.logicalKey;
                              if (key == LogicalKeyboardKey.select || key == LogicalKeyboardKey.enter) {
                                Navigator.of(context).pop();
                                return KeyEventResult.handled;
                              }
                            }
                            return KeyEventResult.ignored;
                          },
                          child: Builder(
                            builder: (context) {
                              final isFocused = Focus.of(context).hasFocus;
                              return GestureDetector(
                                onTap: () => Navigator.of(context).pop(),
                                child: Container(
                                  padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
                                  decoration: BoxDecoration(
                                    color: isFocused ? Colors.redAccent.withValues(alpha: 0.25) : Colors.transparent,
                                    borderRadius: BorderRadius.circular(12),
                                    border: Border.all(
                                      color: isFocused ? Colors.redAccent : Colors.white12,
                                    ),
                                  ),
                                  child: Row(
                                    children: [
                                      Icon(Icons.close_rounded, color: isFocused ? Colors.redAccent : Colors.white60, size: 20),
                                      const SizedBox(width: 10),
                                      Text(
                                        'Ayarları Kapat',
                                        style: TextStyle(
                                          color: isFocused ? Colors.white : Colors.white70,
                                          fontWeight: FontWeight.w600,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                      ],
                    ),
                  ),

                  // SAĞ PANEL: DETAY İÇERİK
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: _buildRightPanelContent(),
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  Widget _buildTabButton(int index, IconData icon, String title) {
    final isSelected = _selectedTab == index;
    return Focus(
      autofocus: index == 0,
      onFocusChange: (hasFocus) {
        if (hasFocus) {
          setState(() => _selectedTab = index);
        }
      },
      onKeyEvent: (node, event) {
        if (event is KeyDownEvent) {
          final key = event.logicalKey;
          if (key == LogicalKeyboardKey.select ||
              key == LogicalKeyboardKey.enter) {
            setState(() => _selectedTab = index);
            return KeyEventResult.handled;
          }
        }
        return KeyEventResult.ignored;
      },
      child: Builder(
        builder: (context) {
          final isFocused = Focus.of(context).hasFocus;
          return GestureDetector(
            onTap: () => setState(() => _selectedTab = index),
            child: Container(
              margin: const EdgeInsets.symmetric(vertical: 4),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                color: isFocused
                    ? TVTheme.focusCyan.withValues(alpha: 0.25)
                    : (isSelected ? Colors.white.withValues(alpha: 0.08) : Colors.transparent),
                borderRadius: BorderRadius.circular(14),
                border: Border.all(
                  color: isFocused ? TVTheme.focusCyan : Colors.transparent,
                  width: 1.5,
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    icon,
                    size: 20,
                    color: isFocused || isSelected ? TVTheme.focusCyan : Colors.white60,
                  ),
                  const SizedBox(width: 12),
                  Text(
                    title,
                    style: TextStyle(
                      color: isFocused || isSelected ? Colors.white : Colors.white70,
                      fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                      fontSize: 14,
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildRightPanelContent() {
    switch (_selectedTab) {
      case 0:
        return _buildGeneralSettings();
      case 1:
        return _buildChannelOrderSettings();
      case 2:
        return _buildRemoteAndCastSettings();
      case 3:
        return _buildSleepTimerSettings();
      case 4:
      default:
        return _buildSystemSettings();
    }
  }

  // 1. GENEL & YAYIN AYARLARI
  Widget _buildGeneralSettings() {
    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Genel & Canlı Yayın Tercihleri', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
          const SizedBox(height: 14),

          // Tampon Modu
          Focus(
            onKeyEvent: (node, event) {
              if (event is KeyDownEvent) {
                final key = event.logicalKey;
                if (key == LogicalKeyboardKey.select ||
                    key == LogicalKeyboardKey.enter ||
                    key == LogicalKeyboardKey.space) {
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
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: isFocused ? TVTheme.focusCyan.withValues(alpha: 0.22) : Colors.white.withValues(alpha: 0.04),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(
                        color: isFocused ? TVTheme.focusCyan : Colors.white12,
                        width: isFocused ? 2.0 : 1.0,
                      ),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.speed_rounded, color: TVTheme.focusCyan, size: 28),
                        const SizedBox(width: 14),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('Yayın Kararlılık & Tampon: ${_bufferMode.toUpperCase()}', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 15)),
                              const SizedBox(height: 2),
                              Text(
                                _bufferMode == 'ultra'
                                    ? 'Ultra Canlı: 8 sn tampon (minimum gecikme)'
                                    : (_bufferMode == 'shield'
                                        ? 'Zırhlı Mod: 25 sn tampon (donmayı tamamen sıfırlar)'
                                        : 'Kesintisiz & Dengeli: 16 sn tampon (ideal güvenlik)'),
                                style: TextStyle(color: TVTheme.textSecondary.withValues(alpha: 0.8), fontSize: 12),
                              ),
                            ],
                          ),
                        ),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(color: TVTheme.focusCyan, borderRadius: BorderRadius.circular(8)),
                          child: const Text('Değiştir (OK)', style: TextStyle(color: Colors.black, fontWeight: FontWeight.bold, fontSize: 12)),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 10),

          _buildSwitchRow(
            icon: Icons.power_settings_new_rounded,
            title: 'TV Açıldığında Otomatik Başlat',
            subtitle: 'Televizyon açılır açılmaz doğrudan AydTV başlasın.',
            value: _autoStartOnBoot,
            onChanged: (v) {
              setState(() => _autoStartOnBoot = v);
              _saveSetting('auto_start_on_boot', v);
            },
          ),
          const SizedBox(height: 8),

          _buildSwitchRow(
            icon: Icons.tv_rounded,
            title: 'Ekranı Sürekli Açık Tut (Uyku Engelle)',
            subtitle: 'Yayın esnasında televizyonun hareketsizlikten kapanmasını önler.',
            value: _keepScreenOn,
            onChanged: (v) {
              setState(() => _keepScreenOn = v);
              _saveSetting('keep_screen_on', v);
            },
          ),
          const SizedBox(height: 8),

          _buildSwitchRow(
            icon: Icons.volume_off_rounded,
            title: 'Arka Plana Geçince Sesi Durdur',
            subtitle: 'TV ana menüsüne veya başka uygulamaya geçince yayının sesi kesilsin.',
            value: _muteOnBackground,
            onChanged: (v) {
              setState(() => _muteOnBackground = v);
              _saveSetting('mute_on_background', v);
            },
          ),
        ],
      ),
    );
  }

  // 2. KANAL SIRALAMA AYARLARI
  Widget _buildChannelOrderSettings() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: const [
                Text('Kanal Sıralamasını Düzenle', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
                SizedBox(height: 2),
                Text('Seçili kanala OK basıp Yukarı/Aşağı taşıyın, tekrar OK ile kilitleyin.', style: TextStyle(color: TVTheme.textSecondary, fontSize: 12)),
              ],
            ),
            TextButton.icon(
              icon: const Icon(Icons.refresh_rounded, size: 16, color: TVTheme.focusCyan),
              label: const Text('Sıfırla', style: TextStyle(color: TVTheme.focusCyan, fontSize: 13)),
              onPressed: () async {
                await _channelService.resetChannelOrder();
                final original = await _channelService.loadInitialChannels();
                setState(() => _orderableChannels = original);
                widget.onChannelsReordered?.call(original);
              },
            ),
          ],
        ),
        const SizedBox(height: 12),

        Expanded(
          child: Container(
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.03),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.white10),
            ),
            child: ListView.builder(
              itemCount: _orderableChannels.length,
              itemBuilder: (context, index) {
                final ch = _orderableChannels[index];
                final isMoving = _movingChannelIndex == index;

                return Focus(
                  key: ValueKey(ch.id),
                  onKeyEvent: (node, event) {
                    if (event is KeyDownEvent) {
                      final key = event.logicalKey;
                      if (key == LogicalKeyboardKey.select ||
                          key == LogicalKeyboardKey.enter ||
                          key == LogicalKeyboardKey.space ||
                          key == LogicalKeyboardKey.gameButtonA) {
                        setState(() {
                          if (_movingChannelIndex != null) {
                            if (_movingChannelIndex == index) {
                              _movingChannelIndex = null;
                            } else {
                              _moveChannel(_movingChannelIndex!, index);
                              _movingChannelIndex = null;
                            }
                          } else {
                            _movingChannelIndex = index;
                          }
                        });
                        return KeyEventResult.handled;
                      } else if (isMoving && (key == LogicalKeyboardKey.arrowUp || key == LogicalKeyboardKey.channelUp)) {
                        _moveChannel(index, index - 1);
                        return KeyEventResult.handled;
                      } else if (isMoving && (key == LogicalKeyboardKey.arrowDown || key == LogicalKeyboardKey.channelDown)) {
                        _moveChannel(index, index + 1);
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
                          setState(() {
                            if (_movingChannelIndex != null) {
                              if (_movingChannelIndex == index) {
                                _movingChannelIndex = null;
                              } else {
                                _moveChannel(_movingChannelIndex!, index);
                                _movingChannelIndex = null;
                              }
                            } else {
                              _movingChannelIndex = index;
                            }
                          });
                        },
                        borderRadius: BorderRadius.circular(12),
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 150),
                          margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                          decoration: BoxDecoration(
                            color: isMoving
                                ? Colors.greenAccent.withValues(alpha: 0.28)
                                : (isFocused ? TVTheme.focusCyan.withValues(alpha: 0.22) : Colors.transparent),
                            borderRadius: BorderRadius.circular(12),
                            border: Border.all(
                              color: isMoving
                                  ? Colors.greenAccent
                                  : (isFocused ? TVTheme.focusCyan : Colors.white10),
                              width: (isMoving || isFocused) ? 2.0 : 1.0,
                            ),
                          ),
                          child: Row(
                            children: [
                              Container(
                                width: 34,
                                height: 34,
                                decoration: BoxDecoration(
                                  color: isMoving ? Colors.greenAccent : TVTheme.focusCyan.withValues(alpha: 0.2),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Center(
                                  child: Text(
                                    '${index + 1}',
                                    style: TextStyle(
                                      color: isMoving ? Colors.black : Colors.white,
                                      fontWeight: FontWeight.bold,
                                      fontSize: 13,
                                    ),
                                  ),
                                ),
                              ),
                              const SizedBox(width: 14),
                              Expanded(
                                child: Text(
                                  ch.name,
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontSize: 15,
                                    fontWeight: (isMoving || isFocused) ? FontWeight.bold : FontWeight.w600,
                                  ),
                                ),
                              ),
                              if (isMoving) ...[
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                  margin: const EdgeInsets.only(right: 6),
                                  decoration: BoxDecoration(
                                    color: Colors.greenAccent.withValues(alpha: 0.2),
                                    borderRadius: BorderRadius.circular(6),
                                  ),
                                  child: const Text(
                                    '▲ ▼ Tuşlarıyla Taşıyın',
                                    style: TextStyle(color: Colors.greenAccent, fontSize: 12, fontWeight: FontWeight.bold),
                                  ),
                                ),
                                IconButton(
                                  icon: const Icon(Icons.arrow_upward_rounded, color: Colors.greenAccent, size: 22),
                                  tooltip: 'Yukarı Taşı',
                                  onPressed: () => _moveChannel(index, index - 1),
                                ),
                                IconButton(
                                  icon: const Icon(Icons.arrow_downward_rounded, color: Colors.greenAccent, size: 22),
                                  tooltip: 'Aşağı Taşı',
                                  onPressed: () => _moveChannel(index, index + 1),
                                ),
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                                  decoration: BoxDecoration(
                                    color: Colors.greenAccent,
                                    borderRadius: BorderRadius.circular(6),
                                  ),
                                  child: const Text(
                                    'Kilitle (OK)',
                                    style: TextStyle(color: Colors.black, fontSize: 12, fontWeight: FontWeight.bold),
                                  ),
                                ),
                              ] else
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                                  decoration: BoxDecoration(
                                    color: isFocused ? TVTheme.focusCyan : Colors.white10,
                                    borderRadius: BorderRadius.circular(6),
                                  ),
                                  child: Text(
                                    'Taşı (OK)',
                                    style: TextStyle(
                                      color: isFocused ? Colors.black : Colors.white60,
                                      fontSize: 12,
                                      fontWeight: isFocused ? FontWeight.bold : FontWeight.normal,
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ),
      ],
    );
  }

  // 3. TELEFONDAN KUMANDA & AIRPLAY AYARLARI
  Widget _buildRemoteAndCastSettings() {
    final remoteUrl = 'http://$_localIp:8088';
    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Telefondan Akıllı Kumanda & AirPlay', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
          const SizedBox(height: 14),

          // Akıllı Kumanda Karekod & Bağlantı Kartı
          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [TVTheme.focusCyan.withValues(alpha: 0.15), Colors.white.withValues(alpha: 0.04)],
              ),
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.4)),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                // Karekod (QR Kod)
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(16),
                    boxShadow: [
                      BoxShadow(
                        color: TVTheme.focusCyan.withValues(alpha: 0.35),
                        blurRadius: 18,
                        spreadRadius: 2,
                      ),
                    ],
                  ),
                  child: QrImageView(
                    data: remoteUrl,
                    version: QrVersions.auto,
                    size: 140,
                    backgroundColor: Colors.white,
                    padding: const EdgeInsets.all(2),
                  ),
                ),
                const SizedBox(width: 20),

                // Açıklama ve Adres
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                            decoration: BoxDecoration(
                              color: TVTheme.focusCyan.withValues(alpha: 0.2),
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: const Text('SIFIR KURULUM', style: TextStyle(color: TVTheme.focusCyan, fontSize: 11, fontWeight: FontWeight.bold)),
                          ),
                          const SizedBox(width: 8),
                          const Text('Mobil Web Kumanda', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 16)),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '1. Telefonunuzun kamerasını açın ve soldaki karekodu okutun.\n2. Veya telefon tarayıcınızdan aşağıdaki adresi açın:',
                        style: TextStyle(color: TVTheme.textSecondary.withValues(alpha: 0.95), fontSize: 13, height: 1.4),
                      ),
                      const SizedBox(height: 10),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.6),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.5)),
                        ),
                        child: Text(
                          remoteUrl,
                          style: const TextStyle(
                            color: TVTheme.focusCyan,
                            fontWeight: FontWeight.bold,
                            fontSize: 15,
                            letterSpacing: 0.5,
                          ),
                        ),
                      ),
                      const SizedBox(height: 6),
                      const Text(
                        'iPhone, iPad ve tüm Android cihazların tarayıcılarında anında çalışır.',
                        style: TextStyle(color: Colors.white38, fontSize: 11),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          _buildSwitchRow(
            icon: Icons.airplay_rounded,
            title: 'Kablosuz Ekran Paylaşımı (AirPlay & DLNA)',
            subtitle: 'iPhone, iPad, Mac ve Android cihazların ekranını TV\'ye yansıtmasını sağlar.',
            value: _castEnabled,
            onChanged: (v) {
              setState(() => _castEnabled = v);
              _saveSetting('cast_enabled', v);
            },
          ),
          const SizedBox(height: 8),

          if (_castEnabled)
            _buildSwitchRow(
              icon: Icons.lock_outline_rounded,
              title: 'AirPlay PIN Kodu Koruması',
              subtitle: 'İlk kez bağlanan yabancı cihazlardan TV ekranında rastgele 4 haneli kod ister.',
              value: _castPinEnabled,
              onChanged: (v) {
                setState(() => _castPinEnabled = v);
                _saveSetting('cast_pin_enabled', v);
              },
            ),
        ],
      ),
    );
  }

  // 4. UYKU ZAMANLAYICISI
  Widget _buildSleepTimerSettings() {
    final activeMinutes = _sleepService.isActive ? (_sleepService.remainingSeconds ~/ 60) + 1 : 0;
    final options = [0, 15, 30, 45, 60, 90, 120];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Uyku Zamanlayıcısı (Otomatik Kapanma)', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
        const SizedBox(height: 6),
        Text(
          'Gece televizyon karşısında uyuyakaldığınızda yayını otomatik durdurur ve ekranı kapatır.',
          style: TextStyle(color: TVTheme.textSecondary.withValues(alpha: 0.8), fontSize: 13),
        ),
        const SizedBox(height: 18),

        // Kalan Süre Göstergesi
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          decoration: BoxDecoration(
            color: _sleepService.isActive ? TVTheme.focusCyan.withValues(alpha: 0.15) : Colors.white.withValues(alpha: 0.04),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: _sleepService.isActive ? TVTheme.focusCyan : Colors.white12),
          ),
          child: Row(
            children: [
              Icon(Icons.timer_rounded, color: _sleepService.isActive ? TVTheme.focusCyan : Colors.white54, size: 28),
              const SizedBox(width: 14),
              Expanded(
                child: Text(
                  _sleepService.isActive
                      ? '⏱️ Kalan Süre: ${_sleepService.remainingFormatted} sonra yayın durdurulacak'
                      : 'Zamanlayıcı şu an kapalı.',
                  style: TextStyle(
                    color: _sleepService.isActive ? Colors.white : Colors.white70,
                    fontWeight: FontWeight.bold,
                    fontSize: 15,
                  ),
                ),
              ),
              if (_sleepService.isActive)
                ElevatedButton(
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.redAccent, foregroundColor: Colors.white),
                  onPressed: () {
                    _sleepService.cancelTimer();
                    setState(() {});
                  },
                  child: const Text('İptal Et'),
                ),
            ],
          ),
        ),
        const SizedBox(height: 20),

        const Text('Hızlı Süre Seçimi:', style: TextStyle(color: Colors.white70, fontWeight: FontWeight.bold, fontSize: 14)),
        const SizedBox(height: 10),

        Wrap(
          spacing: 12,
          runSpacing: 10,
          children: options.map((m) {
            final isSelected = (m == 0 && !_sleepService.isActive) || (m > 0 && activeMinutes == m);
            return Focus(
              onKeyEvent: (node, event) {
                if (event is KeyDownEvent) {
                  final key = event.logicalKey;
                  if (key == LogicalKeyboardKey.select ||
                      key == LogicalKeyboardKey.enter ||
                      key == LogicalKeyboardKey.space) {
                    if (m == 0) {
                      _sleepService.cancelTimer();
                    } else {
                      _sleepService.startTimer(m);
                    }
                    setState(() {});
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
                      if (m == 0) {
                        _sleepService.cancelTimer();
                      } else {
                        _sleepService.startTimer(m);
                      }
                      setState(() {});
                    },
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                      decoration: BoxDecoration(
                        color: isFocused
                            ? TVTheme.focusCyan
                            : (isSelected ? TVTheme.focusCyan.withValues(alpha: 0.3) : Colors.white.withValues(alpha: 0.05)),
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(
                          color: isFocused || isSelected ? TVTheme.focusCyan : Colors.white12,
                          width: 1.5,
                        ),
                      ),
                      child: Text(
                        m == 0 ? 'Kapat' : '$m Dakika',
                        style: TextStyle(
                          color: isFocused ? Colors.black : Colors.white,
                          fontWeight: FontWeight.bold,
                          fontSize: 14,
                        ),
                      ),
                    ),
                  );
                },
              ),
            );
          }).toList(),
        ),
      ],
    );
  }

  // 5. SİSTEM & TV TUŞU AYARLARI
  Widget _buildSystemSettings() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Sistem & Televizyon Entegrasyonu', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
        const SizedBox(height: 14),

        // TV Tuşunu AydTV'ye Ata
        Focus(
          onKeyEvent: (node, event) {
            if (event is KeyDownEvent) {
              final key = event.logicalKey;
              if (key == LogicalKeyboardKey.select ||
                  key == LogicalKeyboardKey.enter ||
                  key == LogicalKeyboardKey.space) {
                Navigator.of(context).pop();
                DefaultTvDialog.show(context);
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
                  DefaultTvDialog.show(context);
                },
                child: Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: isFocused ? TVTheme.focusCyan.withValues(alpha: 0.22) : Colors.white.withValues(alpha: 0.04),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(
                      color: isFocused ? TVTheme.focusCyan : Colors.white12,
                      width: isFocused ? 2.0 : 1.0,
                    ),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.live_tv_rounded, color: TVTheme.focusCyan, size: 28),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: const [
                            Text('📺 TV Tuşunu AydTV\'ye Ata', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 15)),
                            SizedBox(height: 2),
                            Text('Kumandadaki TV / Canlı TV tuşunun doğrudan AydTV\'yi açmasını sağlar.', style: TextStyle(color: TVTheme.textSecondary, fontSize: 12)),
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
        ),
        const SizedBox(height: 12),

        // Güncellemeleri Denetle
        Focus(
          onKeyEvent: (node, event) {
            if (event is KeyDownEvent) {
              final key = event.logicalKey;
              if (key == LogicalKeyboardKey.select ||
                  key == LogicalKeyboardKey.enter ||
                  key == LogicalKeyboardKey.space) {
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
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: isFocused ? TVTheme.focusCyan.withValues(alpha: 0.22) : Colors.white.withValues(alpha: 0.04),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(
                      color: isFocused ? TVTheme.focusCyan : Colors.white12,
                      width: isFocused ? 2.0 : 1.0,
                    ),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.system_update_rounded, color: TVTheme.focusCyan, size: 28),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: const [
                            Text('Güncellemeleri Şimdi Denetle', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 15)),
                            SizedBox(height: 2),
                            Text('GitHub üzerinden en son AydTV APK sürümünü kontrol eder.', style: TextStyle(color: TVTheme.textSecondary, fontSize: 12)),
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
        ),

        const Spacer(),

        Center(
          child: Text(
            'AydTV v${UpdateService.currentVersion} • aydcode.com.tr',
            style: TextStyle(color: Colors.white.withValues(alpha: 0.4), fontSize: 13),
          ),
        ),
      ],
    );
  }

  Widget _buildSwitchRow({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Focus(
      onKeyEvent: (node, event) {
        if (event is KeyDownEvent) {
          final key = event.logicalKey;
          if (key == LogicalKeyboardKey.select ||
              key == LogicalKeyboardKey.enter ||
              key == LogicalKeyboardKey.space) {
            onChanged(!value);
            return KeyEventResult.handled;
          }
        }
        return KeyEventResult.ignored;
      },
      child: Builder(
        builder: (context) {
          final isFocused = Focus.of(context).hasFocus;
          return InkWell(
            onTap: () => onChanged(!value),
            borderRadius: BorderRadius.circular(12),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(
                color: isFocused ? TVTheme.focusCyan.withValues(alpha: 0.22) : Colors.white.withValues(alpha: 0.04),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: isFocused ? TVTheme.focusCyan : Colors.white12,
                  width: isFocused ? 2.0 : 1.0,
                ),
              ),
              child: Row(
                children: [
                  Icon(icon, color: isFocused ? TVTheme.focusCyan : Colors.white70, size: 24),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title,
                          style: TextStyle(
                            color: isFocused ? Colors.white : Colors.white.withValues(alpha: 0.9),
                            fontWeight: isFocused ? FontWeight.bold : FontWeight.w600,
                            fontSize: 14,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(subtitle, style: TextStyle(color: TVTheme.textSecondary.withValues(alpha: 0.8), fontSize: 12)),
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
            ),
          );
        },
      ),
    );
  }
}
