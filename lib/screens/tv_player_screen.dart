import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
import '../models/channel.dart';
import '../services/channel_service.dart';
import '../services/epg_service.dart';
import '../services/update_service.dart';
import '../theme/tv_theme.dart';
import '../widgets/glass_drawer.dart';
import '../widgets/quick_zapping_bar.dart';

class TVPlayerScreen extends StatefulWidget {
  const TVPlayerScreen({super.key});

  @override
  State<TVPlayerScreen> createState() => _TVPlayerScreenState();
}

class _TVPlayerScreenState extends State<TVPlayerScreen> {
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
  Duration? _lastPosition;
  int _stuckCounter = 0;
  bool _isAutoReconnecting = false;
  int _consecutiveStalls = 0;

  final FocusNode _screenFocusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _initializeChannels();
    _checkAppUpdates();
    _startFreezeDetector();
  }

  @override
  void dispose() {
    _freezeCheckTimer?.cancel();
    _zappingBarTimer?.cancel();
    _zappingDebounceTimer?.cancel();
    _numberInputTimer?.cancel();
    _controller?.dispose();
    _screenFocusNode.dispose();
    super.dispose();
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

      // TV+ Gerçek Zamanlı EPG Bilgisini Arka Planda Çek
      _epgService.syncAllEpg(_channels, () {
        if (mounted) setState(() {});
      });
    }

    _syncChannelsInBackground();
  }

  Future<void> _syncChannelsInBackground() async {
    final updatedChannels = await _channelService.syncWithRemote();
    if (updatedChannels != null && mounted) {
      setState(() {
        _channels = updatedChannels;
      });
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
                    Text('İndiriliyor: %${(downloadProgress * 100).toInt()}', style: TVTheme.tvCategory),
                    const SizedBox(height: 8),
                    LinearProgressIndicator(
                      value: downloadProgress,
                      color: TVTheme.focusCyan,
                      backgroundColor: Colors.white12,
                    ),
                  ],
                ],
              ),
              actions: [
                if (!isDownloading) ...[
                  TextButton(
                    onPressed: () => Navigator.pop(context),
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
                      });
                      _updateService.startDownloadAndInstall(update.downloadUrl).listen((event) {
                        setModalState(() {
                          downloadProgress = (int.tryParse(event.value ?? '0') ?? 0) / 100.0;
                        });
                      });
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
      _lastPosition = null;

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

      await newController.play();
      newController.setLooping(true);

      if (mounted && thisRequestId == _playRequestId) {
        setState(() {
          _controller = newController;
          _playingChannelId = channel.id; // Oynatma başarıyla sağlandı!
          _isLoading = false;
        });
        _stuckCounter = 0;
        _lastPosition = null;
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

  /// Canlı Yayın Donma (Stall / Freeze) Dedektörü
  /// Ağ dalgalanmaları veya HLS manifest tıkanmalarında yayının donmasını 3-4 saniyede
  /// tespit eder ve kullanıcı kumandaya dokunmadan canlı uca (live edge) otomatik yeniden bağlanır.
  void _startFreezeDetector() {
    _freezeCheckTimer?.cancel();
    _stuckCounter = 0;
    _lastPosition = null;

    _freezeCheckTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted ||
          _isAutoReconnecting ||
          _isLoading ||
          _hasError ||
          _controller == null ||
          _isDrawerOpen) {
        return;
      }

      final val = _controller!.value;
      if (!val.isInitialized) return;

      // HLS canlı yayınında takılma / donma tespiti:
      // 1) ExoPlayer uzun süre buffer bekliyor (isBuffering = true)
      // 2) Veya oynatılıyor görünüyor ama position ilerlemiyor (ekran dondu)
      final isFrozen = (val.isPlaying && _lastPosition != null && val.position == _lastPosition);
      final isBufferingStuck = val.isBuffering;

      if (isFrozen || isBufferingStuck) {
        _stuckCounter++;
        // 4 saniye boyunca donuk kaldıysa otomatik canlı uca yeniden bağlan
        if (_stuckCounter >= 4) {
          _stuckCounter = 0;
          _recoverStalledPlayback();
        }
      } else {
        // Yayın akmaya devam ediyorsa sayaçları sıfırla
        _stuckCounter = 0;
        _consecutiveStalls = 0;
      }

      _lastPosition = val.position;
    });
  }

  /// Donan yayını kullanıcı kanal değiştirip geri gelmiş gibi canlı uçtan (live-edge) tazeler
  Future<void> _recoverStalledPlayback() async {
    if (_currentChannel == null || _isAutoReconnecting || !mounted) return;

    _isAutoReconnecting = true;
    _consecutiveStalls++;

    _showToast('🔄 Yayın yenileniyor, canlıya bağlanılıyor...');

    final ch = _currentChannel!;
    final idx = _currentChannelIndex;

    // Aynı hat üst üste 2 kez donarsa ve yedek link varsa sıradaki yedeğe geç
    int targetUrlIndex = 0;
    if (_consecutiveStalls >= 2 && ch.backupUrls.isNotEmpty) {
      targetUrlIndex = (_consecutiveStalls - 1) % (ch.backupUrls.length + 1);
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
    } else if (key == LogicalKeyboardKey.arrowDown || key == LogicalKeyboardKey.channelDown) {
      _onChannelStep(1); // Aşağı tuş = sonraki kanal (TV mantığı)
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowUp || key == LogicalKeyboardKey.channelUp) {
      _onChannelStep(-1); // Yukarı tuş = önceki kanal
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
                ),
            ],
          ),
        ),
      ),
    );
  }
}
