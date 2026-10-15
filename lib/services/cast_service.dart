import 'dart:async';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

class CastMediaInfo {
  final String mediaType; // 'video' | 'audio' | 'screen'
  final String url;
  final String title;
  final String artist;
  final String album;

  CastMediaInfo({
    required this.mediaType,
    required this.url,
    required this.title,
    required this.artist,
    required this.album,
  });

  bool get isAudioOnly => mediaType == 'audio';
}

class CastService {
  static final CastService _instance = CastService._internal();
  factory CastService() => _instance;
  CastService._internal();

  static const MethodChannel _channel = MethodChannel('com.aydtv.aydtv/cast');

  // Callback'ler
  void Function(String deviceName, String type)? onDeviceConnected;
  VoidCallback? onDeviceDisconnected;
  void Function(CastMediaInfo media)? onMediaStarted;
  VoidCallback? onMediaStopped;
  VoidCallback? onMediaPaused;
  VoidCallback? onMediaResumed;
  void Function(double position, double duration)? onPositionChanged;

  bool _isServerRunning = false;
  bool get isServerRunning => _isServerRunning;

  CastMediaInfo? currentMedia;
  String? connectedDeviceName;

  void initialize() {
    _channel.setMethodCallHandler(_handleMethodCall);
    _autoStartIfEnabled();
  }

  Future<void> _autoStartIfEnabled() async {
    final prefs = await SharedPreferences.getInstance();
    final isEnabled = prefs.getBool('cast_enabled') ?? true;
    final pinEnabled = prefs.getBool('cast_pin_enabled') ?? false;
    final deviceName = prefs.getString('cast_device_name') ?? 'AydTV';

    if (isEnabled) {
      await startServer(name: deviceName, pinEnabled: pinEnabled);
    }
  }

  Future<void> startServer({String name = 'AydTV', bool pinEnabled = false}) async {
    try {
      await _channel.invokeMethod('startServer', {
        'deviceName': name,
        'pinEnabled': pinEnabled,
      });
      _isServerRunning = true;
    } catch (_) {}
  }

  Future<void> stopServer() async {
    try {
      await _channel.invokeMethod('stopServer');
      _isServerRunning = false;
    } catch (_) {}
  }

  Future<void> sendPlay() async {
    try {
      await _channel.invokeMethod('sendRemoteCommand', {'action': 'play'});
    } catch (_) {}
  }

  Future<void> sendPause() async {
    try {
      await _channel.invokeMethod('sendRemoteCommand', {'action': 'pause'});
    } catch (_) {}
  }

  Future<void> sendSeek(double secondsOffset) async {
    try {
      await _channel.invokeMethod('sendRemoteCommand', {
        'action': 'seek',
        'value': secondsOffset,
      });
    } catch (_) {}
  }

  Future<void> disconnect() async {
    try {
      await _channel.invokeMethod('sendRemoteCommand', {'action': 'disconnect'});
    } catch (_) {}
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onDeviceConnected':
        final args = call.arguments as Map?;
        final name = (args?['name'] ?? 'Apple Cihazı').toString();
        final type = (args?['type'] ?? 'airplay').toString();
        connectedDeviceName = name;
        onDeviceConnected?.call(name, type);
        break;

      case 'onDeviceDisconnected':
        connectedDeviceName = null;
        currentMedia = null;
        onDeviceDisconnected?.call();
        break;

      case 'onMediaStarted':
        final args = call.arguments as Map?;
        final media = CastMediaInfo(
          mediaType: (args?['mediaType'] ?? 'video').toString(),
          url: (args?['url'] ?? '').toString(),
          title: (args?['title'] ?? 'AirPlay Medyası').toString(),
          artist: (args?['artist'] ?? 'Apple Cihazı').toString(),
          album: (args?['album'] ?? '').toString(),
        );
        currentMedia = media;
        onMediaStarted?.call(media);
        break;

      case 'onMediaStopped':
        currentMedia = null;
        onMediaStopped?.call();
        break;

      case 'onMediaPaused':
        onMediaPaused?.call();
        break;

      case 'onMediaResumed':
        onMediaResumed?.call();
        break;

      case 'onPositionChanged':
        final args = call.arguments as Map?;
        final pos = (args?['position'] as num?)?.toDouble() ?? 0.0;
        final dur = (args?['duration'] as num?)?.toDouble() ?? 100.0;
        onPositionChanged?.call(pos, dur);
        break;
    }
  }
}
