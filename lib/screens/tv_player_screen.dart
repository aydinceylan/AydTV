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
    _controller?.dispose();
    _screenFocusNode.dispose();
    super.dispose();
  }

  /// Kanalları yerel önbellekten anında yükler ve son kanalı başlatır
  Future<void> _initializeChannels() async {
    final channels = await _channelService.loadInitialChannels();
    if (channels.isNotEmpty && mounted) {
      setState(() {
        _channels = channels;
      });

      // Son izlenen kanalı bul veya ilk kanaldan başla
      final lastId = await _channelService.getLastChannelId();
      int startIndex = 0;
      if (lastId != null) {
        final found = channels.indexWhere((c) => c.id == lastId);
        if (found != -1) startIndex = found;
      }

      _playChannel(channels[startIndex], startIndex);
    }

    // Arka planda GitHub'dan yeni listeyi kontrol et
    _syncChannelsInBackground();
  }

  Future<void> _syncChannelsInBackground() async {
    final updatedChannels = await _channelService.syncWithRemote();
    if (updatedChannels != null && mounted) {
      setState(() {
        _channels = updatedChannels;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: const Text(
            '✨ Kanal listesi GitHub üzerinden güncellendi!',
            style: TextStyle(fontSize: 16),
          ),
          backgroundColor: TVTheme.focusCyan.withValues(alpha: 0.9),
          duration: const Duration(seconds: 3),
        ),
      );
    }
  }

  /// GitHub Releases üzerinden yeni APK kontrolü
  Future<void> _checkAppUpdates() async {
    final update = await _updateService.checkForUpdate();
    if (update != null && mounted) {
      _showUpdateDialog(update);
    }
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
                  const Icon(Icons.system_update_rounded, color: TVTheme.focusCyan, size: 32),
                  const SizedBox(width: 12),
                  Text('Yeni AydTV Sürümü (v${update.version})', style: TVTheme.tvTitle),
                ],
              ),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (update.releaseNotes.isNotEmpty) ...[
                    Text('Yenilikler:', style: TVTheme.tvChannelName.copyWith(fontSize: 18)),
                    const SizedBox(height: 6),
                    Text(update.releaseNotes, style: TVTheme.tvCategory),
                    const SizedBox(height: 16),
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
                    child: const Text('Daha Sonra', style: TextStyle(color: TVTheme.textSecondary, fontSize: 16)),
                  ),
                  ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: TVTheme.focusCyan,
                      foregroundColor: Colors.black,
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
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
                    child: const Text('Hemen Güncelle', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                  ),
                ],
              ],
            );
          },
        );
      },
    );
  }

  /// Kanalı oynatır
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

  /// Bir sonraki kanal
  void _nextChannel() {
    if (_channels.isEmpty) return;
    int next = (_currentChannelIndex + 1) % _channels.length;
    _playChannel(_channels[next], next);
  }

  /// Bir önceki kanal
  void _prevChannel() {
    if (_channels.isEmpty) return;
    int prev = (_currentChannelIndex - 1 + _channels.length) % _channels.length;
    _playChannel(_channels[prev], prev);
  }

  /// TV Kumandası Tuş Yakalayıcı (Arçelik TV D-Pad)
  KeyEventResult _handleKeyEvent(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) {
      return KeyEventResult.ignored;
    }

    final key = event.logicalKey;

    // Menü Açıkken Kumanda Mantığı
    if (_isDrawerOpen) {
      if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
        setState(() {
          _isDrawerOpen = false;
        });
        return KeyEventResult.handled;
      }
      return KeyEventResult.ignored; // Drawer kendi iç focus'unu yönetsin
    }

    // Menü Kapalıyken (Tam Ekrandayken) Kumanda Mantığı
    if (key == LogicalKeyboardKey.select ||
        key == LogicalKeyboardKey.enter ||
        key == LogicalKeyboardKey.space) {
      // OK tuşuna basıldı -> Cam Kanal Menüsünü Aç
      setState(() {
        _isDrawerOpen = true;
        _showZappingBar = false;
      });
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowUp || key == LogicalKeyboardKey.channelUp) {
      // Yukarı tuşu -> Sonraki Kanal
      _nextChannel();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowDown || key == LogicalKeyboardKey.channelDown) {
      // Aşağı tuşu -> Önceki Kanal
      _prevChannel();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowRight) {
      // Sağ tuşu -> Zapping Bar + Sonraki Kanal
      _nextChannel();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.arrowLeft) {
      // Sol tuşu -> Zapping Bar + Önceki Kanal
      _prevChannel();
      return KeyEventResult.handled;
    } else if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
      // Geri tuşu -> Uygulamadan Çıkış Onayı
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
        title: const Text('AydTV\'den çıkılsın mı?', style: TVTheme.tvTitle),
        content: const Text('Uygulamayı kapatmak istediğinize emin misiniz?', style: TVTheme.tvCategory),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('İptal', style: TextStyle(color: TVTheme.textSecondary, fontSize: 16)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.redAccent),
            onPressed: () => SystemNavigator.pop(),
            child: const Text('Çıkış', style: TextStyle(color: Colors.white, fontSize: 16)),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Focus(
      focusNode: _screenFocusNode,
      autofocus: true,
      onKeyEvent: _handleKeyEvent,
      child: Scaffold(
        backgroundColor: TVTheme.background,
        body: Stack(
          fit: StackFit.expand,
          children: [
            // 1. TAM EKRAN VİDEO KATMANI (55" TV Uyumu)
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
                      width: 50,
                      height: 50,
                      child: CircularProgressIndicator(
                        color: TVTheme.focusCyan,
                        strokeWidth: 4,
                      ),
                    ),
                    const SizedBox(height: 20),
                    Text(
                      _currentChannel != null ? '${_currentChannel!.name} Açılıyor...' : 'AydTV Yükleniyor...',
                      style: TVTheme.tvChannelName.copyWith(color: TVTheme.focusCyan),
                    ),
                  ],
                ),
              )
            else if (_hasError)
              Center(
                child: Container(
                  padding: const EdgeInsets.all(32),
                  decoration: BoxDecoration(
                    color: TVTheme.surface.withValues(alpha: 0.9),
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: Colors.redAccent.withValues(alpha: 0.5)),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.cloud_off_rounded, color: Colors.redAccent, size: 64),
                      const SizedBox(height: 16),
                      Text('Yayın Hatası', style: TVTheme.tvTitle.copyWith(color: Colors.redAccent)),
                      const SizedBox(height: 8),
                      Text(_errorMessage, style: TVTheme.tvCategory),
                      const SizedBox(height: 20),
                      ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: TVTheme.focusCyan,
                          foregroundColor: Colors.black,
                          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                        ),
                        onPressed: () {
                          if (_currentChannel != null) {
                            _playChannel(_currentChannel!, _currentChannelIndex);
                          }
                        },
                        icon: const Icon(Icons.refresh_rounded),
                        label: const Text('Tekrar Dene', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                      ),
                    ],
                  ),
                ),
              ),

            // 2. HIZLI ZAPPING BARI (Sağ/Sol Tuşuna Basınca veya Kanal Değişince Altta Çıkar)
            if (_showZappingBar && _currentChannel != null && !_isDrawerOpen)
              QuickZappingBar(
                channel: _currentChannel!,
                channelIndex: _currentChannelIndex,
                totalChannels: _channels.length,
              ),

            // 3. CAM EFEKTLİ KANAL LİSTESİ (OK Tuşuna Basınca Açılır)
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
    );
  }
}
