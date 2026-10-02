import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
import '../models/channel.dart';
import '../services/channel_service.dart';
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

  List<Channel> _channels = [];
  Channel? _currentChannel;
  int _currentChannelIndex = 0;

  VideoPlayerController? _controller;
  bool _isLoading = true;
  bool _hasError = false;
  String _errorMessage = '';

  bool _isDrawerOpen = false;
  bool _showZappingBar = false;
  Timer? _zappingBarTimer;

  // Sayı Tuşlarıyla Kanal Değiştirme (0-9)
  String _numberInputBuffer = '';
  Timer? _numberInputTimer;

  final FocusNode _screenFocusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _initializeChannels();
    _checkAppUpdates();
  }

  @override
  void dispose() {
    _zappingBarTimer?.cancel();
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

      _playChannel(channels[startIndex], startIndex);
    }

    _syncChannelsInBackground();
  }

  Future<void> _syncChannelsInBackground() async {
    final updatedChannels = await _channelService.syncWithRemote();
    if (updatedChannels != null && mounted) {
      setState(() {
        _channels = updatedChannels;
      });
      _showToast('✨ Kanal listesi GitHub üzerinden güncellendi!');
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

  Future<void> _playChannel(Channel channel, int index) async {
    if (_currentChannel?.id == channel.id && _controller != null && _controller!.value.isPlaying) {
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

    final oldController = _controller;
    _controller = null;
    await oldController?.dispose();

    try {
      final uri = Uri.parse(channel.url);
      final newController = VideoPlayerController.networkUrl(
        uri,
        httpHeaders: channel.headers ?? {},
      );

      await newController.initialize();
      await newController.play();
      newController.setLooping(true);

      if (mounted) {
        setState(() {
          _controller = newController;
          _isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _hasError = true;
          _errorMessage = 'Yayın başlatılamadı. Link geçici olarak çevrimdışı olabilir.';
        });
      }
    }
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

  void _nextChannel() {
    if (_channels.isEmpty) return;
    int next = (_currentChannelIndex + 1) % _channels.length;
    _playChannel(_channels[next], next);
  }

  void _prevChannel() {
    if (_channels.isEmpty) return;
    int prev = (_currentChannelIndex - 1 + _channels.length) % _channels.length;
    _playChannel(_channels[prev], prev);
  }

  void _handleNumberInput(String digit) {
    _numberInputTimer?.cancel();
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

  void _toggleCurrentFavorite() {
    if (_currentChannel != null) {
      _channelService.toggleFavorite(_currentChannel!);
      setState(() {});
      _showToast(
        _currentChannel!.isFavorite
            ? '⭐ ${_currentChannel!.name} Favorilere Eklendi'
            : '⚪ ${_currentChannel!.name} Favorilerden Çıkarıldı',
      );
    }
  }

  KeyEventResult _handleKeyEvent(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) {
      return KeyEventResult.ignored;
    }

    final key = event.logicalKey;

    // Kumanda Kırmızı Tuş Kontrolü
    if (_isRedKey(event)) {
      _toggleCurrentFavorite();
      return KeyEventResult.handled;
    }

    // Sayı Tuşları (0-9)
    final keyLabel = event.character ?? '';
    if (RegExp(r'^[0-9]$').hasMatch(keyLabel)) {
      _handleNumberInput(keyLabel);
      return KeyEventResult.handled;
    }

    // Menü Açıkken Kumanda Mantığı
    if (_isDrawerOpen) {
      if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
        setState(() {
          _isDrawerOpen = false;
        });
        _screenFocusNode.requestFocus();
        return KeyEventResult.handled;
      }
      return KeyEventResult.ignored;
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
      _nextChannel();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowUp || key == LogicalKeyboardKey.channelUp) {
      _prevChannel();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowRight) {
      _nextChannel();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowLeft) {
      _prevChannel();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
      _showExitDialog();
      return KeyEventResult.handled;
    }

    return KeyEventResult.ignored;
  }

  void _showExitDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
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
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('İptal', style: TextStyle(color: TVTheme.textSecondary, fontSize: 15)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.redAccent,
              foregroundColor: Colors.white,
            ),
            onPressed: () => SystemNavigator.pop(),
            child: const Text('Çıkış', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        // Menü açıksa SADECE menüyü kapat, kesinlikle uygulamadan çıkma!
        if (_isDrawerOpen) {
          setState(() {
            _isDrawerOpen = false;
          });
          _screenFocusNode.requestFocus();
          return;
        }
        // Menü kapalıysa onay sor
        _showExitDialog();
      },
      child: Focus(
        focusNode: _screenFocusNode,
        autofocus: true,
        onKeyEvent: _handleKeyEvent,
        child: Scaffold(
          backgroundColor: TVTheme.background,
          body: Stack(
            fit: StackFit.expand,
            children: [
              // 1. TAM EKRAN VİDEO KATMANI
              if (_controller != null && _controller!.value.isInitialized)
                Center(
                  child: AspectRatio(
                    aspectRatio: _controller!.value.aspectRatio > 0
                        ? _controller!.value.aspectRatio
                        : 16 / 9,
                    child: VideoPlayer(_controller!),
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
                        const SizedBox(height: 16),
                        ElevatedButton.icon(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: TVTheme.focusCyan,
                            foregroundColor: Colors.black,
                            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
                          ),
                          onPressed: () {
                            if (_currentChannel != null) {
                              _playChannel(_currentChannel!, _currentChannelIndex);
                            }
                          },
                          icon: const Icon(Icons.refresh_rounded),
                          label: const Text('Tekrar Dene', style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold)),
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
                    final idx = _channels.indexWhere((c) => c.id == ch.id);
                    _playChannel(ch, idx != -1 ? idx : 0);
                  },
                  onToggleFavorite: (ch) {
                    _channelService.toggleFavorite(ch);
                  },
                  onClose: () {
                    setState(() {
                      _isDrawerOpen = false;
                    });
                    _screenFocusNode.requestFocus();
                  },
                ),
            ],
          ),
        ),
      ),
    );
  }
}
