import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import '../models/channel.dart';

class EpgProgram {
  final String name;
  final DateTime startTime;
  final DateTime endTime;

  EpgProgram({
    required this.name,
    required this.startTime,
    required this.endTime,
  });

  bool isCurrent(DateTime now) {
    return (now.isAfter(startTime) || now.isAtSameMomentAs(startTime)) &&
        now.isBefore(endTime);
  }

  String get timeFormatted {
    final sH = startTime.hour.toString().padLeft(2, '0');
    final sM = startTime.minute.toString().padLeft(2, '0');
    final eH = endTime.hour.toString().padLeft(2, '0');
    final eM = endTime.minute.toString().padLeft(2, '0');
    return '$sH:$sM - $eH:$eM';
  }
}

class EpgService {
  static final EpgService _instance = EpgService._internal();
  factory EpgService() => _instance;
  EpgService._internal();

  static const String _authUrl = 'https://izmaottvsc14.tvplus.com.tr:33207/EPG/JSON/Authenticate';
  static const String _playbillUrl = 'https://izmaottvsc14.tvplus.com.tr:33207/EPG/JSON/PlayBillList';

  String? _sessionCookie;
  DateTime? _lastAuthTime;

  final Map<String, List<EpgProgram>> _cache = {};

  HttpClient _createClient() {
    final client = HttpClient();
    client.badCertificateCallback = (X509Certificate cert, String host, int port) => true;
    client.connectionTimeout = const Duration(seconds: 5);
    return client;
  }

  Future<bool> _authenticate() async {
    if (_sessionCookie != null &&
        _lastAuthTime != null &&
        DateTime.now().difference(_lastAuthTime!).inHours < 8) {
      return true;
    }

    try {
      final client = _createClient();
      final request = await client.postUrl(Uri.parse(_authUrl));
      request.headers.set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
      request.headers.set(HttpHeaders.userAgentHeader, 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)');

      final authPayload = jsonEncode({
        "terminaltype": "webtv",
        "terminalvendor": "Mozilla/5.0",
        "osversion": "Win32",
        "userType": "3",
        "utcEnable": "1",
        "timezone": "Europe/Istanbul"
      });

      request.write(authPayload);
      final response = await request.close();

      final cookies = response.headers[HttpHeaders.setCookieHeader];
      if (cookies != null && cookies.isNotEmpty) {
        _sessionCookie = cookies.join('; ');
        _lastAuthTime = DateTime.now();
        client.close();
        return true;
      }
      client.close();
    } catch (e) {
      if (kDebugMode) {
        print('EPG Auth hatası: $e');
      }
    }
    return false;
  }

  /// TV+'tan gelen "2026-10-07 23:15:00 UTC+03:00" formatını eksiksiz parse eder
  DateTime? _parseDateTime(String? raw) {
    if (raw == null || raw.trim().isEmpty) return null;
    try {
      String cleaned = raw.trim();
      cleaned = cleaned.replaceAll(' UTC', '');
      cleaned = cleaned.replaceFirst(' ', 'T');
      // Format: "2026-10-07T23:15:00+03:00"
      final parsed = DateTime.tryParse(cleaned);
      if (parsed != null) {
        return parsed.toLocal();
      }
      // Yedek parse
      if (raw.length >= 19) {
        final d = raw.substring(0, 10);
        final t = raw.substring(11, 19);
        return DateTime.tryParse('${d}T$t')?.toLocal();
      }
    } catch (_) {}
    return null;
  }

  Future<List<EpgProgram>> fetchChannelSchedule(String epgId) async {
    if (_cache.containsKey(epgId) && _cache[epgId]!.isNotEmpty) {
      return _cache[epgId]!;
    }

    final authed = await _authenticate();
    if (!authed) return [];

    try {
      final now = DateTime.now();
      final year = now.year.toString();
      final month = now.month.toString().padLeft(2, '0');
      final day = now.day.toString().padLeft(2, '0');
      final begintime = '$year$month${day}000000';

      final tomorrow = now.add(const Duration(days: 1));
      final tYear = tomorrow.year.toString();
      final tMonth = tomorrow.month.toString().padLeft(2, '0');
      final tDay = tomorrow.day.toString().padLeft(2, '0');
      final endtime = '$tYear$tMonth${tDay}235959';

      final client = _createClient();
      final request = await client.postUrl(Uri.parse(_playbillUrl));
      request.headers.set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
      if (_sessionCookie != null) {
        request.headers.set(HttpHeaders.cookieHeader, _sessionCookie!);
      }

      final payload = jsonEncode({
        "type": "2",
        "channelid": epgId,
        "begintime": begintime,
        "endtime": endtime,
        "isFillProgram": 1
      });

      request.write(payload);
      final response = await request.close();
      final respBody = await response.transform(utf8.decoder).join();
      client.close();

      final Map<String, dynamic> data = jsonDecode(respBody);
      final List<dynamic>? items = data['playbilllist'];

      if (items != null) {
        final List<EpgProgram> programs = [];
        for (var it in items) {
          final name = it['name']?.toString() ?? '';
          final st = _parseDateTime(it['starttime']?.toString());
          final et = _parseDateTime(it['endtime']?.toString());

          if (name.isNotEmpty && st != null && et != null) {
            programs.add(EpgProgram(name: name, startTime: st, endTime: et));
          }
        }
        _cache[epgId] = programs;
        return programs;
      }
    } catch (e) {
      if (kDebugMode) {
        print('Kanal ($epgId) EPG çekme hatası: $e');
      }
    }
    return [];
  }

  /// O anki canlı program bilgisini DateTime.now()'a göre dinamik ve anlık hesaplar
  String? getCurrentProgramInfo(String? epgId) {
    if (epgId == null || !_cache.containsKey(epgId)) return null;

    final now = DateTime.now();
    final programs = _cache[epgId]!;
    if (programs.isEmpty) return null;

    // 1. Canlı olarak şu an yayında olan programı bul
    for (var p in programs) {
      if (p.isCurrent(now)) {
        return '${p.timeFormatted} ${p.name}';
      }
    }

    // 2. Tam aralık denk gelmediyse, şu anki saate en yakın programı seç
    EpgProgram? closest;
    Duration? minDiff;
    for (var p in programs) {
      final diff = (p.startTime.difference(now)).abs();
      if (minDiff == null || diff < minDiff) {
        minDiff = diff;
        closest = p;
      }
    }

    if (closest != null) {
      return '${closest.timeFormatted} ${closest.name}';
    }

    return null;
  }

  /// Tüm kanalların yayın akışını arka planda çeker ve UI'ı günceller
  Future<void> syncAllEpg(List<Channel> channels, VoidCallback onUpdate) async {
    final epgChannels = channels.where((c) => c.epgId != null && c.epgId!.isNotEmpty).toList();
    if (epgChannels.isEmpty) return;

    final ok = await _authenticate();
    if (!ok) return;

    bool hasAnyUpdate = false;

    const batchSize = 4;
    for (int i = 0; i < epgChannels.length; i += batchSize) {
      final batch = epgChannels.skip(i).take(batchSize);
      await Future.wait(batch.map((ch) async {
        final programs = await fetchChannelSchedule(ch.epgId!);
        if (programs.isNotEmpty) {
          final liveInfo = getCurrentProgramInfo(ch.epgId);
          if (liveInfo != null && liveInfo.isNotEmpty) {
            ch.currentProgram = liveInfo;
            hasAnyUpdate = true;
          }
        }
      }));

      if (hasAnyUpdate) {
        onUpdate();
      }
    }
  }
}
