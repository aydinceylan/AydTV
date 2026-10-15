import 'dart:async';
import 'package:flutter/foundation.dart';

class SleepTimerService {
  static final SleepTimerService _instance = SleepTimerService._internal();
  factory SleepTimerService() => _instance;
  SleepTimerService._internal();

  Timer? _timer;
  int _remainingSeconds = 0;
  int get remainingSeconds => _remainingSeconds;
  bool get isActive => _remainingSeconds > 0;

  VoidCallback? onExpired;
  VoidCallback? onTick;

  String get remainingFormatted {
    if (!isActive) return 'Kapalı';
    final mins = _remainingSeconds ~/ 60;
    final secs = _remainingSeconds % 60;
    return '$mins:${secs.toString().padLeft(2, '0')}';
  }

  void startTimer(int minutes, {VoidCallback? onTimeUp}) {
    cancelTimer();
    if (minutes <= 0) return;

    if (onTimeUp != null) onExpired = onTimeUp;
    _remainingSeconds = minutes * 60;

    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (_remainingSeconds > 0) {
        _remainingSeconds--;
        onTick?.call();
      } else {
        cancelTimer();
        onExpired?.call();
      }
    });
    onTick?.call();
  }

  void cancelTimer() {
    _timer?.cancel();
    _timer = null;
    _remainingSeconds = 0;
    onTick?.call();
  }
}
