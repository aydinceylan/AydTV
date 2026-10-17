import 'dart:async';
import 'dart:io';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ota_update/ota_update.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:video_player/video_player.dart';
import '../models/channel.dart';
import '../services/channel_service.dart';
import '../services/epg_service.dart';
import '../services/update_service.dart';
import '../theme/tv_theme.dart';
import '../widgets/default_tv_dialog.dart';
import '../widgets/glass_drawer.dart';
import '../widgets/quick_zapping_bar.dart';
import '../widgets/settings_dialog.dart';
import '../services/cast_service.dart';
import '../widgets/cast_toast.dart';
import '../widgets/cast_music_view.dart';
import '../widgets/cast_disconnect_dialog.dart';
import '../services/sleep_timer_service.dart';

class TVPlayerScreen extends StatefulWidget {
  const TVPlayerScreen({super.key});

  @override
  State<TVPlayerScreen> createState() => _TVPlayerScreenState();
}

class _TVPlayerScreenState extends State<TVPlayerScreen> with WidgetsBindingObserver {
  final ChannelService _channelService = ChannelService();
  final UpdateService _updateService = UpdateService();
  final EpgService _epgService = EpgService();

  List<Channel> _channels = [];
  Channel? _currentChannel;
  int _currentChannelIndex = 0;
  int _targetChannelIndex = 0;

  VideoPlayerController? _controller;
  String? _playingChannelId;
  bool _isLoading = true;
  bool _hasError = false;
  String _errorMessage = '';

  // Kablosuz Ekran ve Ses Paylaşımı (AirPlay & DLNA)
  final CastService _castService = CastService();
  bool _isCasting = false;
  bool _isCastPlaying = true;
  String? _connectedCastDevice;
  String _castType = 'airplay';
  CastMediaInfo? _currentCastMedia;
  bool _showCastToast = false;
  VideoPlayerController? _castVideoController;
  bool _isCastVideoBuffering = false;

  // Eşzamanlılık / Yarış Durumu (Race Condition) Önleyici İstek Kimliği
  int _playRequestId = 0;
  Timer? _zappingDebounceTimer;

  bool _isDrawerOpen = false;
  DateTime? _lastDrawerClosedAt;
  bool _showZappingBar = false;
  Timer? _zappingBarTimer;

  // Sayı Tuşlarıyla Kanal Değiştirme (0-9)
  String _numberInputBuffer = '';
  Timer? _numberInputTimer;

  // Otomatik Donma (Freeze / Stall) Dedektörü
  Timer? _freezeCheckTimer;
  int _stuckCounter = 0;
  bool _isAutoReconnecting = false;
  int _consecutiveStalls = 0;

  // EPG Canlı Zamanlayıcı (Dakikalık EPG yenileme)
  Timer? _epgUpdateTimer;

  // Yayın Kararlılık & Tamponlama Modu ('ultra', 'balanced', 'shield')
  String _bufferMode = 'balanced';
  bool _isSettingsOpen = false;

  // AirPlay PIN Güvenlik Diyaloğu
  bool _isPinDialogOpen = false;
  String? _airplayPin;
  String? _airplayPinClient;

  // Canlı Ekran Yansıtma (AirPlay Screen Mirroring 60 FPS Hardware Texture)
  int? _mirrorTextureId;
  double _mirrorWidth = 1920.0;
  double _mirrorHeight = 1080.0;
  int _castFrameVersion = 0;

  Future<void> _loadBufferSetting() async {
    final prefs = await SharedPreferences.getInstance();
    if (mounted) {
      setState(() {
        _bufferMode = prefs.getString('buffer_mode') ?? 'balanced';
      });
    }
  }

  int _getFreezeTimeoutSeconds() {
    switch (_bufferMode) {
      case 'ultra':
        return 6;
      case 'shield':
        return 14;
      case 'balanced':
      default:
        return 9;
    }
  }

  final FocusNode _screenFocusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _setupSystemChannelListener();
    _setupCastListener();
    _loadBufferSetting();
    _initializeChannels();
    _checkAppUpdates();
    _startFreezeDetector();
    _epgUpdateTimer = Timer.periodic(const Duration(minutes: 1), (_) {
      if (mounted) setState(() {});
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        DefaultTvDialog.checkAndShow(context);
      }
    });

    SleepTimerService().onExpired = () {
      if (!mounted) return;
      _controller?.setVolume(0.0);
      _controller?.pause();
      _showToast('⏱️ Uyku Zamanlayıcı: Süre doldu, yayın duraklatıldı.');
    };
  }

  void _setupCastListener() {
    _castService.initialize();

    _castService.onDeviceConnected = (deviceName, type) {
      if (!mounted) return;
      setState(() {
        _connectedCastDevice = deviceName;
        _castType = type;
        _showCastToast = true;
      });
    };

    _castService.onDeviceDisconnected = () {
      if (!mounted) return;
      _disposeCastVideo();
      setState(() {
        _isCasting = false;
        _currentCastMedia = null;
        _connectedCastDevice = null;
        _showCastToast = false;
      });
      // Canlı TV yayınını tekrar başlat
      _controller?.setVolume(1.0);
      _controller?.play();
    };

    _castService.onMediaStarted = (media) {
      if (!mounted) return;
      _stuckCounter = 0;
      if (media.mediaType == 'photo' && media.url.isNotEmpty) {
        try {
          FileImage(File(media.url)).evict();
        } catch (_) {}
      }
      setState(() {
        _isCasting = true;
        _isCastPlaying = true;
        _currentCastMedia = media;
        _castFrameVersion++;
      });
      // Canlı TV yayınını duraklat ve sesini tamamen kes
      _controller?.setVolume(0.0);
      _controller?.pause();

      if (media.mediaType == 'video' && media.url.isNotEmpty) {
        _initCastVideo(media.url);
      }
    };

    _castService.onMediaStopped = () {
      if (!mounted) return;
      _disposeCastVideo();
      setState(() {
        _isCasting = false;
        _currentCastMedia = null;
      });
      // Canlı TV yayınını kaldığı yerden devam ettir
      _controller?.setVolume(1.0);
      _controller?.play();
    };

    _castService.onMediaPaused = () {
      if (!mounted) return;
      _castVideoController?.pause();
      setState(() => _isCastPlaying = false);
    };

    _castService.onMediaResumed = () {
      if (!mounted) return;
      _castVideoController?.play();
      setState(() => _isCastPlaying = true);
    };

    _castService.onShowPinCode = (pin, client) {
      if (!mounted) return;
      _showAirPlayPinDialog(pin, client);
    };

    _castService.onMirrorStarted = (textureId, width, height) {
      if (!mounted) return;
      _stuckCounter = 0;
      setState(() {
        _mirrorTextureId = textureId;
        _mirrorWidth = width > 0 ? width : 1920.0;
        _mirrorHeight = height > 0 ? height : 1080.0;
        _isCasting = true;
        _connectedCastDevice = _castService.connectedDeviceName ?? 'Apple Cihazı';
      });
      // Canlı TV yayınını duraklat ve sesini tamamen kes
      _controller?.setVolume(0.0);
      _controller?.pause();
    };

    _castService.onMirrorStopped = () {
      if (!mounted) return;
      setState(() {
        _mirrorTextureId = null;
        if (_currentCastMedia == null) {
          _isCasting = false;
        }
      });
      if (!_isCasting) {
        _controller?.setVolume(1.0);
        _controller?.play();
      }
    };

    _castService.onHidePinCode = () {
      if (!mounted) return;
      _hideAirPlayPinDialog();
    };

    // Telefondan Akıllı Web Kumanda Komutları
    _castService.onRemoteCommand = (action, value) {
      if (!mounted) return;

      // 1. Ekranda bir modal/diyalog (Ayarlar, Çıkış vb.) açıksa D-Pad navigasyonunu o pencereye aktar
      if (_isSettingsOpen || _isExitDialogOpen) {
        switch (action) {
          case 'up':
            FocusManager.instance.primaryFocus?.focusInDirection(TraversalDirection.up);
            return;
          case 'down':
            FocusManager.instance.primaryFocus?.focusInDirection(TraversalDirection.down);
            return;
          case 'left':
            FocusManager.instance.primaryFocus?.focusInDirection(TraversalDirection.left);
            return;
          case 'right':
            FocusManager.instance.primaryFocus?.focusInDirection(TraversalDirection.right);
            return;
          case 'ok':
            final primary = FocusManager.instance.primaryFocus;
            if (primary != null && primary.context != null) {
              Actions.invoke(primary.context!, const ActivateIntent());
            }
            return;
          case 'back':
          case 'settings':
            // Açık olan modal pencereyi kapat!
            Navigator.of(context, rootNavigator: true).pop();
            return;
        }
      }

      // 2. Kanal Çekmecesi açıksa
      if (_isDrawerOpen) {
        switch (action) {
          case 'back':
            _closeDrawer();
            return;
          case 'up':
            _onChannelStep(-1);
            return;
          case 'down':
            _onChannelStep(1);
            return;
          case 'ok':
            _closeDrawer();
            return;
        }
      }

      // 3. Normal Tam Ekran Canlı TV Modu (Global TV Standardı: Yukarı/CH+ = Sonraki Kanal +1, Aşağı/CH- = Önceki Kanal -1)
      switch (action) {
        case 'up':
        case 'channel_up':
          _onChannelStep(1);
          break;
        case 'down':
        case 'channel_down':
          _onChannelStep(-1);
          break;
        case 'left':
          _onChannelStep(-1);
          break;
        case 'right':
          _onChannelStep(1);
          break;
        case 'ok':
          setState(() {
            _isDrawerOpen = true;
            _showZappingBar = false;
          });
          break;
        case 'back':
          if (_isCasting) {
            _castService.disconnect();
          } else {
            _showExitDialog();
          }
          break;
        case 'settings':
          _openSettingsDialog();
          break;
        case 'channel_num':
          if (value != null) {
            _handleNumberInput(value.toString());
          }
          break;
        case 'select_channel':
          if (value != null) {
            final targetId = value.toString();
            final idx = _channels.indexWhere((c) => c.id == targetId || c.name.toLowerCase() == targetId.toLowerCase());
            if (idx != -1) {
              _targetChannelIndex = idx;
              _playChannel(_channels[idx], idx);
              _showToast('Açıldı: ${_channels[idx].name}');
            }
          }
          break;
        case 'toggle_favorite':
          if (value != null) {
            final targetId = value.toString();
            final ch = _channels.firstWhere((c) => c.id == targetId, orElse: () => _channels.first);
            if (ch.id == targetId) {
              _channelService.toggleFavorite(ch).then((_) {
                if (mounted) {
                  setState(() {});
                  _syncChannelsWithCastService();
                  _showToast(ch.isFavorite ? '${ch.name} favorilere eklendi' : '${ch.name} favorilerden çıkarıldı');
                }
              });
            }
          }
          break;
        case 'sleep_timer':
          final minutes = (value is num) ? value.toInt() : (int.tryParse(value?.toString() ?? '') ?? 0);
          if (minutes > 0) {
            SleepTimerService().startTimer(minutes, onTimeUp: () {
              if (mounted) {
                _controller?.pause();
                _showToast('Uyku zamanı: TV yayını durduruldu.');
              }
            });
            _showToast('Uyku Zamanlayıcı: $minutes dakika ayarlandı');
          } else {
            SleepTimerService().cancelTimer();
            _showToast('Uyku Zamanlayıcı kapatıldı');
          }
          _syncChannelsWithCastService();
          _syncSettingsWithCastService();
          break;
        case 'text_input':
          if (value != null && value.toString().trim().isNotEmpty) {
            _showToast('Telefondan: $value');
          }
          break;
        case 'setting_update':
          if (value is Map) {
            final k = value['key']?.toString() ?? '';
            final v = value['value'];
            _handleRemoteSettingUpdate(k, v);
          }
          break;
        case 'play_pause':
          if (_controller != null) {
            if (_controller!.value.isPlaying) {
              _controller?.pause();
              _showToast('Yayın Duraklatıldı');
            } else {
              _controller?.play();
              _showToast('Yayın Oynatılıyor');
            }
            _syncChannelsWithCastService();
          }
          break;
        case 'reload':
          if (_currentChannel != null) {
            _playChannel(_currentChannel!, _currentChannelIndex);
            _showToast('Yayın yeniden başlatılıyor');
          }
          break;
        case 'reorder_channels':
          if (value is List) {
            final orderIds = value.map((e) => e.toString()).toList();
            final map = {for (var c in _channels) c.id: c};
            final ordered = <Channel>[];
            for (var id in orderIds) {
              if (map.containsKey(id)) {
                ordered.add(map.remove(id)!);
              }
            }
            ordered.addAll(map.values);
            setState(() {
              _channels = ordered;
              if (_currentChannel != null) {
                final idx = _channels.indexWhere((c) => c.id == _currentChannel!.id);
                _currentChannelIndex = idx != -1 ? idx : 0;
                _targetChannelIndex = _currentChannelIndex;
              }
            });
            _channelService.saveChannelOrder(ordered);
            _syncChannelsWithCastService();
            _showToast('Kanal sıralaması telefondan güncellendi');
          }
          break;
      }
    };
  }

  Future<void> _syncSettingsWithCastService() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final settings = {
        'buffer_mode': prefs.getString('buffer_mode') ?? 'balanced',
        'keep_screen_on': prefs.getBool('keep_screen_on') ?? true,
        'mute_on_background': prefs.getBool('mute_on_background') ?? true,
        'auto_start_on_boot': prefs.getBool('auto_start_on_boot') ?? true,
        'cast_enabled': prefs.getBool('cast_enabled') ?? true,
        'cast_pin_enabled': prefs.getBool('cast_pin_enabled') ?? false,
        'sleep_minutes': SleepTimerService().isActive ? (SleepTimerService().remainingSeconds ~/ 60) + 1 : 0,
      };
      _castService.updateSettings(settings);
    } catch (_) {}
  }

  Future<void> _handleRemoteSettingUpdate(String key, dynamic val) async {
    final prefs = await SharedPreferences.getInstance();
    if (key == 'buffer_mode') {
      await prefs.setString('buffer_mode', val.toString());
      _loadBufferSetting();
      _showToast('Tampon Modu: ${val.toString().toUpperCase()}');
    } else if (key == 'keep_screen_on') {
      final b = val == true || val.toString() == 'true';
      await prefs.setBool('keep_screen_on', b);
      _showToast(b ? 'Ekran sürekli açık tutulacak' : 'Standart ekran modu');
    } else if (key == 'mute_on_background') {
      final b = val == true || val.toString() == 'true';
      await prefs.setBool('mute_on_background', b);
      _showToast(b ? 'Arka planda ses kesilecek' : 'Arka planda ses açık');
    } else if (key == 'auto_start_on_boot') {
      final b = val == true || val.toString() == 'true';
      await prefs.setBool('auto_start_on_boot', b);
      _showToast(b ? 'Açılışta otomatik başlama açık' : 'Açılışta otomatik başlama kapalı');
    } else if (key == 'cast_enabled') {
      final b = val == true || val.toString() == 'true';
      await prefs.setBool('cast_enabled', b);
      _showToast(b ? 'AirPlay / DLNA Aktif' : 'AirPlay / DLNA Kapalı');
    } else if (key == 'cast_pin_enabled') {
      final b = val == true || val.toString() == 'true';
      await prefs.setBool('cast_pin_enabled', b);
      _showToast(b ? 'AirPlay PIN Koruması Açık' : 'AirPlay PIN Koruması Kapalı');
      _castService.startServer(pinEnabled: b);
    }
    _syncSettingsWithCastService();
  }

  void _showAirPlayPinDialog(String pin, String? client) {
    if (!mounted) return;
    setState(() {
      _airplayPin = pin;
      _airplayPinClient = client;
      _isPinDialogOpen = true;
    });
  }

  void _hideAirPlayPinDialog() {
    if (!mounted) return;
    setState(() {
      _isPinDialogOpen = false;
      _airplayPin = null;
      _airplayPinClient = null;
    });
  }

  void _syncChannelsWithCastService() {
    if (!mounted || _channels.isEmpty) return;
    final list = _channels.map((c) => {
      'id': c.id,
      'name': c.name,
      'logo': c.logo,
      'isFavorite': c.isFavorite,
    }).toList();
    _castService.updateChannels(list);
    _castService.updateStatus(
      currentChannel: _currentChannel?.name,
      channelId: _currentChannel?.id,
      channelNumber: _currentChannel != null ? _channels.indexOf(_currentChannel!) + 1 : 1,
      sleepSeconds: SleepTimerService().remainingSeconds,
      isPlaying: _controller?.value.isPlaying ?? true,
    );
  }

  void _setupSystemChannelListener() {
    const MethodChannel('com.aydtv.aydtv/system').setMethodCallHandler((call) async {
      if (call.method == 'forceStopAudio') {
        _controller?.setVolume(0.0);
        _controller?.pause();
      } else if (call.method == 'resumeAudio') {
        _controller?.setVolume(1.0);
        _controller?.play();
      }
    });
  }

  Future<void> _initCastVideo(String url) async {
    try {
      _disposeCastVideo();
      final controller = VideoPlayerController.networkUrl(Uri.parse(url));
      _castVideoController = controller;
      await controller.initialize();
      controller.play();
      controller.addListener(() {
        if (!mounted) return;
        final isBuff = controller.value.isBuffering;
        if (isBuff != _isCastVideoBuffering) {
          setState(() => _isCastVideoBuffering = isBuff);
        }
      });
      if (mounted) setState(() {});
    } catch (e) {
      debugPrint('AirPlay video başlatma hatası: $e');
    }
  }

  void _disposeCastVideo() {
    try {
      _castVideoController?.pause();
      _castVideoController?.dispose();
    } catch (_) {}
    _castVideoController = null;
    _isCastVideoBuffering = false;
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _disposeCastVideo();
    _epgUpdateTimer?.cancel();
    _freezeCheckTimer?.cancel();
    _zappingBarTimer?.cancel();
    _zappingDebounceTimer?.cancel();
    _numberInputTimer?.cancel();
    _controller?.dispose();
    _screenFocusNode.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    // TV kumandasından Home tuşuna basıldığında veya başka uygulamaya geçildiğinde sesi ve yayını derhal durdur
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive ||
        state == AppLifecycleState.hidden ||
        state == AppLifecycleState.detached) {
      _controller?.setVolume(0.0);
      _controller?.pause();
    } else if (state == AppLifecycleState.resumed) {
      _controller?.setVolume(1.0);
      _controller?.play();
    }
  }

  Future<void> _initializeChannels() async {
    final channels = await _channelService.loadInitialChannels();
    if (channels.isNotEmpty && mounted) {
      setState(() {
        _channels = channels;
      });

      final lastId = await _channelService.getLastChannelId();
      int startIndex = 0;
      if (lastId != null) {
        final found = channels.indexWhere((c) => c.id == lastId);
        if (found != -1) startIndex = found;
      }

      _targetChannelIndex = startIndex;
      _playChannel(channels[startIndex], startIndex);

      // Logoları arka planda RAM belleğine alarak kanal listesinde anında görünmesini sağla
      _preloadLogos(channels);

      // TV+ Gerçek Zamanlı EPG Bilgisini Arka Planda Çek
      _epgService.syncAllEpg(_channels, () {
        if (mounted) setState(() {});
      });

      _syncChannelsWithCastService();
    }

    _syncChannelsInBackground();
  }

  void _preloadLogos(List<Channel> channels) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      for (var ch in channels.take(45)) {
        if (ch.logo.isNotEmpty && mounted) {
          precacheImage(
            CachedNetworkImageProvider(
              ch.logo,
              maxHeight: 120,
              maxWidth: 120,
            ),
            context,
          );
        }
      }
    });
  }

  Future<void> _syncChannelsInBackground() async {
    final updatedChannels = await _channelService.syncWithRemote();
    if (updatedChannels != null && mounted) {
      setState(() {
        _channels = updatedChannels;
      });
      _preloadLogos(_channels);
      _epgService.syncAllEpg(_channels, () {
        if (mounted) setState(() {});
      });
    }
  }

  Future<void> _checkAppUpdates() async {
    final update = await _updateService.checkForUpdate();
    if (update != null && mounted) {
      _showUpdateDialog(update);
    }
  }

  void _openSettingsDialog() {
    if (_isSettingsOpen) {
      // Zaten açıksa kapat (üst üste açılmasını engelle / toggle)
      if (mounted) {
        Navigator.of(context, rootNavigator: true).pop();
      }
      return;
    }
    _isSettingsOpen = true;

    SettingsDialog.show(
      context,
      channels: _channels,
      onChannelsReordered: (newOrder) {
        setState(() {
          _channels = newOrder;
        });
        _syncChannelsWithCastService();
      },
      onSettingsChanged: () {
        _loadBufferSetting();
        _syncSettingsWithCastService();
      },
      onCheckUpdate: () async {
        _showToast('Güncellemeler denetleniyor...');
        final update = await _updateService.checkForUpdate();
        if (update != null && mounted) {
          _showUpdateDialog(update);
        } else if (mounted) {
          _showToast('Tebrikler! En son sürümü (v${UpdateService.currentVersion}) kullanıyorsunuz.');
        }
      },
    ).then((_) {
      if (mounted) {
        setState(() {
          _isSettingsOpen = false;
        });
        _screenFocusNode.requestFocus();
      }
    });
  }

  void _showToast(String message) {
    ScaffoldMessenger.of(context).clearSnackBars();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          message,
          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: Colors.white),
        ),
        backgroundColor: const Color(0xE60D1422),
        duration: const Duration(seconds: 3),
        behavior: SnackBarBehavior.floating,
        margin: const EdgeInsets.only(bottom: 24, left: 60, right: 60),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: const BorderSide(color: TVTheme.focusCyan, width: 1.2),
        ),
      ),
    );
  }

  void _showUpdateDialog(UpdateInfo update) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) {
        double downloadProgress = 0;
        bool isDownloading = false;
        String statusText = '';

        return StatefulBuilder(
          builder: (context, setModalState) {
            return AlertDialog(
              backgroundColor: TVTheme.surface,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(20),
                side: const BorderSide(color: TVTheme.focusCyan, width: 2),
              ),
              title: Row(
                children: [
                  const Icon(Icons.system_update_rounded, color: TVTheme.focusCyan, size: 28),
                  const SizedBox(width: 10),
                  Text('Yeni Sürüm (v${update.version})', style: TVTheme.tvTitle),
                ],
              ),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (update.releaseNotes.isNotEmpty) ...[
                    Text(update.releaseNotes, style: TVTheme.tvCategory),
                    const SizedBox(height: 14),
                  ],
                  if (isDownloading) ...[
                    Text(
                      statusText.isNotEmpty
                          ? statusText
                          : 'İndiriliyor: %${(downloadProgress * 100).toInt()}',
                      style: TVTheme.tvCategory,
                    ),
                    const SizedBox(height: 8),
                    LinearProgressIndicator(
                      value: downloadProgress > 0 ? downloadProgress : null,
                      color: TVTheme.focusCyan,
                      backgroundColor: Colors.white12,
                    ),
                  ],
                ],
              ),
              actions: [
                if (!isDownloading) ...[
                  TextButton(
                    onPressed: () {
                      _updateService.dismissUpdate(update.version);
                      Navigator.pop(context);
                    },
                    child: const Text('Daha Sonra', style: TextStyle(color: TVTheme.textSecondary, fontSize: 15)),
                  ),
                  ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: TVTheme.focusCyan,
                      foregroundColor: Colors.black,
                    ),
                    onPressed: () {
                      setModalState(() {
                        isDownloading = true;
                        statusText = 'İndirme başlatılıyor...';
                      });
                      _updateService.startDownloadAndInstall(update.downloadUrl).listen(
                        (event) {
                          setModalState(() {
                            if (event.status == OtaStatus.DOWNLOADING) {
                              downloadProgress = (int.tryParse(event.value ?? '0') ?? 0) / 100.0;
                              statusText = 'İndiriliyor: %${(downloadProgress * 100).toInt()}';
                            } else if (event.status == OtaStatus.INSTALLING) {
                              statusText = 'İndirme tamamlandı! Yükleme ekranı açılıyor...';
                            } else if (event.status == OtaStatus.PERMISSION_NOT_GRANTED_ERROR) {
                              statusText = 'Lütfen TV Ayarlarından Bilinmeyen Kaynaklara İzin Verin!';
                            } else if (event.status == OtaStatus.INTERNAL_ERROR) {
                              statusText = 'Yükleme başlatılamadı (${event.value})';
                            }
                          });
                        },
                        onError: (e) {
                          setModalState(() {
                            statusText = 'Hata: $e';
                          });
                        },
                      );
                    },
                    child: const Text('Hemen Güncelle', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
                  ),
                ],
              ],
            );
          },
        );
      },
    );
  }

  /// Çoklu Kaynak ve Yarış Durumu (Race Condition) Korumalı Oynatıcı
  Future<void> _playChannel(Channel channel, int index, {int urlIndex = 0, int? requestId}) async {
    final allUrls = [channel.url, ...channel.backupUrls];
    final thisRequestId = requestId ?? ++_playRequestId;

    if (urlIndex == 0) {
      _targetChannelIndex = index;

      // Eğer gerçekten bu kanal zaten video playerda çalıyorsa tekrar başlatma
      if (_playingChannelId == channel.id &&
          _controller != null &&
          _controller!.value.isInitialized &&
          _controller!.value.isPlaying) {
        return;
      }

      setState(() {
        _isLoading = true;
        _hasError = false;
        _currentChannel = channel;
        _currentChannelIndex = index;
      });

      _channelService.saveLastChannelId(channel.id);
      _triggerZappingBar();
      _stuckCounter = 0;

      final oldController = _controller;
      _controller = null;
      _playingChannelId = null;
      await oldController?.dispose();

      // Eski istekse durdur
      if (thisRequestId != _playRequestId) {
        return;
      }
    }

    if (urlIndex >= allUrls.length) {
      if (mounted && thisRequestId == _playRequestId) {
        setState(() {
          _isLoading = false;
          _hasError = true;
          _errorMessage = 'Yayın başlatılamadı. Tüm (${allUrls.length}) kaynak denendi.';
        });
      }
      return;
    }

    final targetUrl = allUrls[urlIndex];
    VideoPlayerController? newController;

    try {
      final uri = Uri.parse(targetUrl);
      newController = VideoPlayerController.networkUrl(
        uri,
        httpHeaders: channel.headers ?? {},
      );

      // Yayının sağlıklı başlatılması için doğal akışa bırakıyoruz (4s kısıtı kaldırıldı)
      await newController.initialize();

      // Kanal initialize olurken arkada yeni bir kanal seçildiyse bunu iptal et
      if (thisRequestId != _playRequestId) {
        await newController.dispose();
        return;
      }

      // Canlı HLS yayını doğal akışında canlı uçtan (live-edge) başlasın.
      // Canlı yayında geriye yapay seekTo() çağırmak tamponu bozar ve 10sn sonra donma döngüsüne sokar.
      await newController.play();
      newController.setLooping(false); // Canlı yayında döngü (loop) KESİNLİKLE kapalı olmalı!

      if (mounted && thisRequestId == _playRequestId) {
        setState(() {
          _controller = newController;
          _playingChannelId = channel.id; // Oynatma başarıyla sağlandı!
          _isLoading = false;
        });
        _stuckCounter = 0;
          _playbackStartedAt = DateTime.now(); // İlk açılışta gereksiz dedektör tetiklenmesini önle
        _castService.updateStatus(
          currentChannel: channel.name,
          channelId: channel.id,
          channelNumber: _channels.indexOf(channel) + 1,
          isPlaying: true,
          sleepSeconds: SleepTimerService().remainingSeconds,
        );
      }
    } catch (e) {
      // Hata veya iptal anında donanım MediaCodec kaynağını kesinlikle serbest bırak!
      await newController?.dispose();

      if (thisRequestId != _playRequestId) return;

      // Birincil link başarısız olduysa sıradaki yedeği dene
      if (urlIndex + 1 < allUrls.length) {
        await _playChannel(channel, index, urlIndex: urlIndex + 1, requestId: thisRequestId);
      } else {
        if (mounted && thisRequestId == _playRequestId) {
          setState(() {
            _isLoading = false;
            _hasError = true;
            _errorMessage = 'Yayın geçici olarak kullanılamıyor.';
          });
        }
      }
    }
  }

  DateTime? _playbackStartedAt;
  DateTime? _lastRecoverAt;

  /// Akıllı Canlı Yayın Donma (Stall / Freeze) Dedektörü
  /// Canlı HLS akışlarında pozisyon artışı düzensiz olabileceği için yanlış pozisyon kontrolleri kaldırıldı.
  /// Sadece gerçek durma (isBuffering veya !isPlaying veya hasError) durumlarında ve yeterli süre bekledikten sonra devreye girer.
  void _startFreezeDetector() {
    _freezeCheckTimer?.cancel();
    _stuckCounter = 0;

    _freezeCheckTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted ||
          _isCasting ||
          _isAutoReconnecting ||
          _isLoading ||
          _hasError ||
          _controller == null ||
          _isDrawerOpen) {
        if (_isCasting) _stuckCounter = 0;
        return;
      }

      final val = _controller!.value;
      if (!val.isInitialized) return;

      // Kanal yeni açıldıysa ilk 10 saniye dedektörü beklet (ExoPlayer tamponu kursun)
      if (_playbackStartedAt != null &&
          DateTime.now().difference(_playbackStartedAt!).inSeconds < 10) {
        _stuckCounter = 0;
        return;
      }

      // Yakın zamanda otomatik kurtarma yapıldıysa en az 20 saniye tekrar kurtarma yapma (döngüyü önle)
      if (_lastRecoverAt != null &&
          DateTime.now().difference(_lastRecoverAt!).inSeconds < 20) {
        _stuckCounter = 0;
        return;
      }

      // Cast veya Canlı Ekran Yansıtma aktifken TV yayını donma dedektörü çalıştırılmaz
      if (_isCasting || _mirrorTextureId != null) {
        _stuckCounter = 0;
        return;
      }

      // Canlı TV yayınında GERÇEK donma kriterleri:
      // 1) Oynatıcı tampon bekliyor (val.isBuffering == true)
      // 2) Veya oynatıcı durmuş ve oynamıyor (!val.isPlaying)
      // 3) Veya hata oluşmuş (val.hasError)
      final bool isRealStall = val.isBuffering || !val.isPlaying || val.hasError;

      if (isRealStall) {
        _stuckCounter++;
        // Seçilen tampon moduna göre (10-18 sn) gerçek donma sürerse kurtar
        final timeout = _getFreezeTimeoutSeconds() + 4; // Güvenli ek tolerans
        if (_stuckCounter >= timeout) {
          _stuckCounter = 0;
          _recoverStalledPlayback();
        }
      } else {
        // Video oynatılıyor ve buffer beklemiyorsa donma YOKTUR!
        _stuckCounter = 0;
        _consecutiveStalls = 0;
      }
    });
  }

  /// Donan yayını kullanıcı kanal değiştirip geri gelmiş gibi canlı uçtan (live-edge) tazeler
  Future<void> _recoverStalledPlayback() async {
    if (_isCasting || _currentChannel == null || _isAutoReconnecting || !mounted) return;

    _lastRecoverAt = DateTime.now();
    _isAutoReconnecting = true;
    _consecutiveStalls++;

    final ch = _currentChannel!;
    final idx = _currentChannelIndex;

    // Eğer yedek hatlar varsa ve yayın takıldıysa alternatif kaynağı dene
    int targetUrlIndex = 0;
    if (_consecutiveStalls >= 2 && ch.backupUrls.isNotEmpty) {
      targetUrlIndex = (_consecutiveStalls - 1) % (ch.backupUrls.length + 1);
      _showToast('Alternatif yayına (${targetUrlIndex + 1}) bağlanılıyor...');
    } else {
      _showToast('Yayın yenileniyor, canlıya bağlanılıyor...');
    }

    _playingChannelId = null; // Mevcut controller kilidini aç
    await _playChannel(ch, idx, urlIndex: targetUrlIndex, requestId: ++_playRequestId);

    _isAutoReconnecting = false;
  }



  void _triggerZappingBar() {
    _zappingBarTimer?.cancel();
    setState(() {
      _showZappingBar = true;
    });
    _zappingBarTimer = Timer(const Duration(seconds: 4), () {
      if (mounted) {
        setState(() {
          _showZappingBar = false;
        });
      }
    });
  }

  /// Hızlı kanal atlamalarında (Up/Down) HUD anında güncellenir, stream 250ms sonra bağlanır
  void _onChannelStep(int delta) {
    if (_channels.isEmpty) return;
    _targetChannelIndex = (_targetChannelIndex + delta + _channels.length) % _channels.length;
    final targetChannel = _channels[_targetChannelIndex];

    // HUD ve kanal bilgisini ANINDA göster (0 gecikme!)
    setState(() {
      _currentChannel = targetChannel;
      _currentChannelIndex = _targetChannelIndex;
      _showZappingBar = true;
    });
    _triggerZappingBar();

    // Hızlı basımları debounce et, 250ms durulunca hedef kanalı aç
    _zappingDebounceTimer?.cancel();
    _zappingDebounceTimer = Timer(const Duration(milliseconds: 250), () {
      if (mounted) {
        _playChannel(targetChannel, _targetChannelIndex);
      }
    });
  }

  void _handleNumberInput(String digit) {
    _numberInputTimer?.cancel();
    _zappingDebounceTimer?.cancel();
    setState(() {
      if (_numberInputBuffer.length < 3) {
        _numberInputBuffer += digit;
      }
    });

    _numberInputTimer = Timer(const Duration(milliseconds: 1400), () {
      if (_numberInputBuffer.isNotEmpty && mounted) {
        final channelNum = int.tryParse(_numberInputBuffer);
        if (channelNum != null && channelNum > 0) {
          int targetIdx = (channelNum - 1).clamp(0, _channels.length - 1);
          _playChannel(_channels[targetIdx], targetIdx);
        }
        setState(() {
          _numberInputBuffer = '';
        });
      }
    });
  }

  bool _isRedKey(KeyEvent event) {
    final key = event.logicalKey;
    if (key == LogicalKeyboardKey.f1 ||
        key == LogicalKeyboardKey.gameButtonX ||
        key.keyId == 183 ||
        key.keyId == 0x002000000b7 ||
        key.keyId == 0x00000000000000b7) {
      return true;
    }
    if (event.physicalKey == PhysicalKeyboardKey.f1) return true;
    if (key.keyLabel.toLowerCase().contains('red')) return true;
    return false;
  }

  bool _isInfoKey(KeyEvent event) {
    final key = event.logicalKey;
    if (key == LogicalKeyboardKey.info ||
        key == LogicalKeyboardKey.guide ||
        key.keyId == 165 ||
        key.keyId == 172 ||
        key.keyId == 0x002000000a5 ||
        key.keyId == 0x00000000000000a5 ||
        key.keyLabel.toLowerCase().contains('info') ||
        key.keyLabel.toLowerCase().contains('guide')) {
      return true;
    }
    return false;
  }

  void _toggleFavorite(Channel ch) {
    final willBeFav = !ch.isFavorite;
    _channelService.toggleFavorite(ch);
    setState(() {});
    _showToast(
      willBeFav
          ? '⭐ ${ch.name} Favorilere Eklendi'
          : '⚪ ${ch.name} Favorilerden Çıkarıldı',
    );
  }

  void _closeDrawer() {
    _lastDrawerClosedAt = DateTime.now();
    if (_isDrawerOpen) {
      setState(() {
        _isDrawerOpen = false;
      });
    }
    _screenFocusNode.requestFocus();
  }

  KeyEventResult _handleKeyEvent(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) {
      return KeyEventResult.ignored;
    }

    final key = event.logicalKey;

    // Ekran Paylaşımı (AirPlay / DLNA) Aktifken Kumanda Yönetimi
    if (_isCasting) {
      // 1. Geri Tuşu: TV Yayınına Dönüş Onay Diyaloğu
      if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
        CastDisconnectDialog.show(
          context,
          deviceName: _connectedCastDevice,
          onConfirm: () {
            _castService.disconnect();
          },
        );
        return KeyEventResult.handled;
      }

      // 2. OK / Select / PlayPause: Duraklat / Oynat
      if (key == LogicalKeyboardKey.select ||
          key == LogicalKeyboardKey.enter ||
          key == LogicalKeyboardKey.space ||
          key == LogicalKeyboardKey.mediaPlayPause ||
          key == LogicalKeyboardKey.mediaPlay ||
          key == LogicalKeyboardKey.mediaPause) {
        if (_castVideoController != null && _castVideoController!.value.isInitialized) {
          if (_castVideoController!.value.isPlaying) {
            _castVideoController!.pause();
            _showToast('⏸️ Duraklatıldı');
          } else {
            _castVideoController!.play();
            _showToast('▶️ Oynatılıyor');
          }
        } else {
          if (_isCastPlaying) {
            _castService.sendPause();
            _showToast('⏸️ Duraklatıldı');
          } else {
            _castService.sendPlay();
            _showToast('▶️ Oynatılıyor');
          }
        }
        return KeyEventResult.handled;
      }

      // 3. Sağ Tuş / İleri Sar: 10 sn İleri
      if (key == LogicalKeyboardKey.arrowRight || key == LogicalKeyboardKey.mediaFastForward) {
        if (_castVideoController != null && _castVideoController!.value.isInitialized) {
          final pos = _castVideoController!.value.position + const Duration(seconds: 10);
          _castVideoController!.seekTo(pos);
        } else {
          _castService.sendSeek(10);
        }
        _showToast('⏩ 10 sn İleri');
        return KeyEventResult.handled;
      }

      // 4. Sol Tuş / Geri Sar: 10 sn Geri
      if (key == LogicalKeyboardKey.arrowLeft || key == LogicalKeyboardKey.mediaRewind) {
        if (_castVideoController != null && _castVideoController!.value.isInitialized) {
          final pos = _castVideoController!.value.position - const Duration(seconds: 10);
          _castVideoController!.seekTo(pos < Duration.zero ? Duration.zero : pos);
        } else {
          _castService.sendSeek(-10);
        }
        _showToast('⏪ 10 sn Geri');
        return KeyEventResult.handled;
      }

      return KeyEventResult.handled; // Yayına tuş kaçmasını engelle
    }

    // Info Tuşu: Banner HUD'u her zaman açar / gösterir
    if (_isInfoKey(event)) {
      _triggerZappingBar();
      return KeyEventResult.handled;
    }

    // Menü açıkken geri tuşu sadece menüyü kapatır
    if (_isDrawerOpen) {
      if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
        _closeDrawer();
        return KeyEventResult.handled;
      }
      return KeyEventResult.ignored;
    }

    // Kumanda Kırmızı Tuş Kontrolü (Tam ekranda o anki kanalı favoriler)
    if (_isRedKey(event)) {
      if (_currentChannel != null) {
        _toggleFavorite(_currentChannel!);
      }
      return KeyEventResult.handled;
    }

    // Sayı Tuşları (0-9)
    final keyLabel = event.character ?? '';
    if (RegExp(r'^[0-9]$').hasMatch(keyLabel)) {
      _handleNumberInput(keyLabel);
      return KeyEventResult.handled;
    }

    // Kumanda Menü / Ayarlar Tuşu (Tam Ekranda Ayarları Açar)
    if (key == LogicalKeyboardKey.contextMenu ||
        key == LogicalKeyboardKey.settings ||
        key == LogicalKeyboardKey.f4 ||
        key.keyLabel.toLowerCase().contains('menu') ||
        key.keyLabel.toLowerCase().contains('settings')) {
      _openSettingsDialog();
      return KeyEventResult.handled;
    }

    // Menü Kapalıyken (Tam Ekran) Kumanda Mantığı
    if (key == LogicalKeyboardKey.select ||
        key == LogicalKeyboardKey.enter ||
        key == LogicalKeyboardKey.space ||
        key == LogicalKeyboardKey.gameButtonA) {
      setState(() {
        _isDrawerOpen = true;
        _showZappingBar = false;
      });
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowUp || key == LogicalKeyboardKey.channelUp) {
      _onChannelStep(1); // Yukarı tuş / CH+ = Sonraki kanal (+1, Global TV standardı)
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowDown || key == LogicalKeyboardKey.channelDown) {
      _onChannelStep(-1); // Aşağı tuş / CH- = Önceki kanal (-1, Global TV standardı)
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowRight) {
      _onChannelStep(1);
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowLeft) {
      _onChannelStep(-1);
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
      // Donanımsal geri tuşunu PopScope yönetir; böylece diyalogun açılıp anında kapanma çakışması önlenir
      return KeyEventResult.ignored;
    }

    return KeyEventResult.ignored;
  }

  bool _isExitDialogOpen = false;

  void _showExitDialog() {
    if (_isExitDialogOpen || !mounted) return;
    _isExitDialogOpen = true;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: TVTheme.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: const BorderSide(color: TVTheme.focusCyan, width: 1.5),
        ),
        title: const Text('AydTV\'den çıkılsın mı?', style: TVTheme.tvTitle),
        content: const Text(
          'Uygulamayı kapatmak istediğinize emin misiniz?',
          style: TextStyle(color: TVTheme.textSecondary, fontSize: 15),
        ),
        actions: [
          ElevatedButton(
            autofocus: true,
            style: ElevatedButton.styleFrom(
              backgroundColor: TVTheme.liquidCardBg,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            ),
            onPressed: () {
              Navigator.pop(dialogContext);
            },
            child: const Text('İptal', style: TextStyle(fontSize: 15)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.redAccent,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            ),
            onPressed: () {
              Navigator.pop(dialogContext);
              SystemNavigator.pop();
            },
            child: const Text('Çıkış', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
          ),
        ],
      ),
    ).then((_) {
      _isExitDialogOpen = false;
      if (mounted) {
        _screenFocusNode.requestFocus();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        // AirPlay PIN Güvenlik Diyaloğu açıksa kapat
        if (_isPinDialogOpen) {
          _hideAirPlayPinDialog();
          return;
        }
        // Ekran paylaşımı aktifse onay sorup TV yayınına dön
        if (_isCasting) {
          CastDisconnectDialog.show(
            context,
            deviceName: _connectedCastDevice,
            onConfirm: () {
              _castService.disconnect();
            },
          );
          return;
        }
        // Menü açıksa SADECE menüyü kapat, kesinlikle uygulamadan çıkma!
        if (_isDrawerOpen) {
          _closeDrawer();
          return;
        }
        // Eğer menü az önce kapatıldıysa donanımsal geri tuşunu yut, çıkış sorma!
        if (_lastDrawerClosedAt != null &&
            DateTime.now().difference(_lastDrawerClosedAt!) < const Duration(milliseconds: 700)) {
          return;
        }
        // Menü kapalıysa onay sor
        _showExitDialog();
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        body: Focus(
          focusNode: _screenFocusNode,
          autofocus: true,
          onKeyEvent: _handleKeyEvent,
          child: Stack(
            fit: StackFit.expand,
            children: [
              // 1. TAM EKRAN CANLI YAYIN OYNATICI
              if (_controller != null && _controller!.value.isInitialized)
                Positioned.fill(
                  child: FittedBox(
                    fit: BoxFit.cover,
                    child: SizedBox(
                      width: _controller!.value.size.width,
                      height: _controller!.value.size.height,
                      child: VideoPlayer(_controller!),
                    ),
                  ),
                )
              else if (_isLoading)
                Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const SizedBox(
                        width: 40,
                        height: 40,
                        child: CircularProgressIndicator(
                          color: TVTheme.focusCyan,
                          strokeWidth: 3.5,
                        ),
                      ),
                      const SizedBox(height: 14),
                      Text(
                        _currentChannel != null ? '${_currentChannel!.name} Başlatılıyor...' : 'AydTV Yükleniyor...',
                        style: TVTheme.tvChannelName.copyWith(color: TVTheme.focusCyan),
                      ),
                    ],
                  ),
                )
              else if (_hasError)
                Center(
                  child: Container(
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: TVTheme.surface.withValues(alpha: 0.92),
                      borderRadius: BorderRadius.circular(18),
                      border: Border.all(color: Colors.redAccent.withValues(alpha: 0.5)),
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.cloud_off_rounded, color: Colors.redAccent, size: 48),
                        const SizedBox(height: 12),
                        Text('Yayın Hatası', style: TVTheme.tvTitle.copyWith(color: Colors.redAccent)),
                        const SizedBox(height: 6),
                        Text(_errorMessage, style: TVTheme.tvCategory),
                        const SizedBox(height: 14),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                          decoration: BoxDecoration(
                            color: TVTheme.liquidCardBg,
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(color: Colors.white24, width: 0.8),
                          ),
                          child: const Text(
                            'Kanal değiştirmek için  ▲ ▼  |  Kanal Listesi için  OK',
                            style: TextStyle(color: TVTheme.textSecondary, fontSize: 13),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),

              // 2. SAYI TUŞU GİRİŞİ GÖSTERGESİ
              if (_numberInputBuffer.isNotEmpty)
                Positioned(
                  top: 28,
                  right: 40,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.85),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: TVTheme.focusCyan, width: 1.8),
                      boxShadow: [
                        BoxShadow(
                          color: TVTheme.focusCyan.withValues(alpha: 0.35),
                          blurRadius: 14,
                        ),
                      ],
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.dialpad_rounded, color: TVTheme.focusCyan, size: 20),
                        const SizedBox(width: 8),
                        Text(
                          'Kanal: $_numberInputBuffer',
                          style: const TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                            letterSpacing: 2,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),

              // 3. HIZLI ZAPPING & TANITIM BARI (HUD)
              if (_showZappingBar && _currentChannel != null && !_isDrawerOpen)
                QuickZappingBar(
                  channel: _currentChannel!,
                  channelIndex: _currentChannelIndex,
                  totalChannels: _channels.length,
                ),

              // 4. LIQUID GLASS KANAL LİSTESİ
              if (_isDrawerOpen)
                GlassDrawer(
                  channels: _channels,
                  currentChannel: _currentChannel,
                  onChannelSelect: (ch) {
                    _zappingDebounceTimer?.cancel();
                    final idx = _channels.indexWhere((c) => c.id == ch.id);
                    _playChannel(ch, idx != -1 ? idx : 0);
                  },
                  onToggleFavorite: (ch) {
                    _toggleFavorite(ch);
                  },
                  onClose: () {
                    _closeDrawer();
                  },
                  onOpenSettings: () {
                    _closeDrawer();
                    _openSettingsDialog();
                  },
                ),

              // 5. EKRAN PAYLAŞIMI / MÜZİK MODU / FOTOĞRAF KATMANI
              if (_isCasting)
                Positioned.fill(
                  child: _buildCastContent(),
                ),

              // 6. İLK BAĞLANTI BİLDİRİMİ (Floating Toast)
              if (_showCastToast && _connectedCastDevice != null)
                CastToast(
                  deviceName: _connectedCastDevice!,
                  type: _castType,
                  onDismissed: () {
                    if (mounted) setState(() => _showCastToast = false);
                  },
                ),

              // 7. AİPLAY GÜVENLİK PIN KODU PENCERESİ
              if (_isPinDialogOpen && _airplayPin != null)
                Positioned.fill(
                  child: _buildPinCodeOverlay(),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCastContent() {
    // 1. CANLI EKRAN YANSITMA (AirPlay Screen Mirroring 60 FPS Hardware Texture)
    if (_mirrorTextureId != null) {
      return Container(
        color: Colors.black,
        width: double.infinity,
        height: double.infinity,
        child: Stack(
          alignment: Alignment.center,
          children: [
            Center(
              child: AspectRatio(
                aspectRatio: (_mirrorWidth > 0 && _mirrorHeight > 0)
                    ? (_mirrorWidth / _mirrorHeight)
                    : (16 / 9),
                child: Texture(textureId: _mirrorTextureId!),
              ),
            ),
            Positioned(
              top: 24,
              right: 28,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.75),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.4)),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.screen_share_rounded, color: TVTheme.focusCyan, size: 18),
                    const SizedBox(width: 8),
                    Text(
                      '📱 ${_connectedCastDevice ?? "Canlı Ekran"}',
                      style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold),
                    ),
                  ],
                ),
              ),
            ),
            Positioned(
              bottom: 24,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.white12),
                ),
                child: const Text(
                  'Ekran yansıtmayı kapatmak için TV Kumandasından GERİ tuşuna basın',
                  style: TextStyle(color: Colors.white70, fontSize: 13),
                ),
              ),
            ),
          ],
        ),
      );
    }

    if (_currentCastMedia?.isAudioOnly == true) {
      return CastMusicView(
        media: _currentCastMedia!,
        deviceName: _connectedCastDevice,
        isPlaying: _isCastPlaying,
        onTogglePlay: () {
          if (_isCastPlaying) {
            _castService.sendPause();
          } else {
            _castService.sendPlay();
          }
        },
        onSeekForward: () => _castService.sendSeek(10),
        onSeekBackward: () => _castService.sendSeek(-10),
        onDisconnect: () => _castService.disconnect(),
      );
    }

    if (_currentCastMedia?.mediaType == 'photo' && _currentCastMedia!.url.isNotEmpty) {
      return Container(
        color: Colors.black,
        width: double.infinity,
        height: double.infinity,
        child: Stack(
          alignment: Alignment.center,
          children: [
            Center(
              child: Image.file(
                File(_currentCastMedia!.url),
                key: ValueKey('${_currentCastMedia!.url}_$_castFrameVersion'),
                fit: BoxFit.contain,
                filterQuality: FilterQuality.medium,
                gaplessPlayback: true,
                errorBuilder: (context, error, stackTrace) => Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.broken_image_rounded, size: 72, color: Colors.white38),
                    const SizedBox(height: 14),
                    Text(
                      'Fotoğraf görüntülenemedi: $error',
                      style: const TextStyle(color: Colors.white70, fontSize: 16),
                    ),
                  ],
                ),
              ),
            ),
            Positioned(
              top: 24,
              right: 28,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.75),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.4)),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.photo_library_rounded, color: TVTheme.focusCyan, size: 18),
                    const SizedBox(width: 8),
                    Text(
                      '📷 ${_connectedCastDevice ?? "Fotoğraf"}',
                      style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold),
                    ),
                  ],
                ),
              ),
            ),
            Positioned(
              bottom: 24,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.white12),
                ),
                child: const Text(
                  'Fotoğrafı kapatmak için TV Kumandasından GERİ tuşuna basın',
                  style: TextStyle(color: Colors.white70, fontSize: 13),
                ),
              ),
            ),
          ],
        ),
      );
    }

    if (_currentCastMedia?.mediaType == 'video' && _castVideoController != null) {
      return Container(
        color: Colors.black,
        width: double.infinity,
        height: double.infinity,
        child: Stack(
          alignment: Alignment.center,
          children: [
            if (_castVideoController!.value.isInitialized)
              Center(
                child: AspectRatio(
                  aspectRatio: _castVideoController!.value.aspectRatio,
                  child: VideoPlayer(_castVideoController!),
                ),
              )
            else
              const Center(
                child: CircularProgressIndicator(color: TVTheme.focusCyan),
              ),
            if (_isCastVideoBuffering)
              const Center(
                child: CircularProgressIndicator(color: TVTheme.focusCyan),
              ),
            Positioned(
              top: 24,
              right: 28,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.7),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.4)),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.play_circle_fill_rounded, color: TVTheme.focusCyan, size: 18),
                    const SizedBox(width: 8),
                    Text(
                      '🎬 ${_connectedCastDevice ?? "AirPlay"}',
                      style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      );
    }

    return Container(
      color: Colors.black,
      width: double.infinity,
      height: double.infinity,
      child: Center(
        child: Container(
          width: 580,
          padding: const EdgeInsets.symmetric(horizontal: 40, vertical: 36),
          decoration: BoxDecoration(
            color: const Color(0xFF0F172A).withValues(alpha: 0.95),
            borderRadius: BorderRadius.circular(28),
            border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.45), width: 1.5),
            boxShadow: [
              BoxShadow(
                color: TVTheme.focusCyan.withValues(alpha: 0.22),
                blurRadius: 40,
                spreadRadius: 4,
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 76,
                height: 76,
                decoration: BoxDecoration(
                  color: TVTheme.focusCyan.withValues(alpha: 0.15),
                  shape: BoxShape.circle,
                  border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.4), width: 1.5),
                ),
                child: const Icon(Icons.airplay_rounded, size: 42, color: TVTheme.focusCyan),
              ),
              const SizedBox(height: 20),
              Text(
                '📱 ${_connectedCastDevice ?? "Apple / Mobil Cihaz"} Bağlandı',
                style: const TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 10),
              const Text(
                'Telefondan video açtığınızda veya fotoğraf paylaştığınızda TV\'de otomatik tam ekran gösterilir.',
                style: TextStyle(color: Colors.white70, fontSize: 14),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 22),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.06),
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: Colors.white12),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.photo_library_rounded, color: TVTheme.focusCyan, size: 20),
                    const SizedBox(width: 10),
                    const Text(
                      'Mobil Kumanda: Fotoğraf & Video Yansıtma Hazır',
                      style: TextStyle(color: TVTheme.focusCyan, fontSize: 13, fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.white12,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: const Text(
                  'TV Yayınına dönmek için TV Kumandasından GERİ tuşuna basın',
                  style: TextStyle(color: Colors.white60, fontSize: 13),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPinCodeOverlay() {
    final pin = _airplayPin ?? '----';
    return Container(
      color: Colors.black.withValues(alpha: 0.88),
      child: Center(
        child: Container(
          width: 520,
          padding: const EdgeInsets.symmetric(horizontal: 40, vertical: 36),
          decoration: BoxDecoration(
            color: const Color(0xFF0F172A).withValues(alpha: 0.95),
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.5), width: 1.5),
            boxShadow: [
              BoxShadow(
                color: TVTheme.focusCyan.withValues(alpha: 0.25),
                blurRadius: 40,
                spreadRadius: 4,
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 68,
                height: 68,
                decoration: BoxDecoration(
                  color: TVTheme.focusCyan.withValues(alpha: 0.15),
                  shape: BoxShape.circle,
                  border: Border.all(color: TVTheme.focusCyan.withValues(alpha: 0.4), width: 1.5),
                ),
                child: const Icon(Icons.airplay_rounded, size: 36, color: TVTheme.focusCyan),
              ),
              const SizedBox(height: 18),
              const Text(
                'AirPlay Güvenlik Kodu',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 24,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 0.5,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                _airplayPinClient != null
                    ? '${_airplayPinClient!} aygıtında bu kodu girin:'
                    : 'Apple aygıtınızda bu güvenlik kodunu girin:',
                style: const TextStyle(
                  color: Colors.white70,
                  fontSize: 15,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 28),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: pin.split('').map((digit) {
                  return Container(
                    margin: const EdgeInsets.symmetric(horizontal: 8),
                    width: 64,
                    height: 76,
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.08),
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: TVTheme.focusCyan, width: 2),
                      boxShadow: [
                        BoxShadow(
                          color: TVTheme.focusCyan.withValues(alpha: 0.3),
                          blurRadius: 16,
                          spreadRadius: 1,
                        ),
                      ],
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      digit,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 38,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 2,
                      ),
                    ),
                  );
                }).toList(),
              ),
              const SizedBox(height: 28),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.06),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: Colors.white12),
                ),
                child: const Text(
                  'İptal etmek için TV Kumandasından GERİ tuşuna basın',
                  style: TextStyle(color: Colors.white60, fontSize: 13),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
