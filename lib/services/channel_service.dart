import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import '../models/channel.dart';

class ChannelService {
  static const String _cacheKey = 'aydtv_cached_channels';
  static const String _favoritesKey = 'aydtv_favorites';
  static const String _lastChannelKey = 'aydtv_last_channel_id';

  // GitHub üzerinden dinamik güncellenebilir raw URL
  static const String remoteUrl =
      'https://raw.githubusercontent.com/aydinceylan/AydTV/main/channels.json';

  /// Kanalları önce yerel önbellek / asset'ten yükler (0 ms açılış)
  Future<List<Channel>> loadInitialChannels() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final cachedJson = prefs.getString(_cacheKey);
      final favoriteIds = prefs.getStringList(_favoritesKey) ?? [];

      String rawData = '';
      if (cachedJson != null && cachedJson.isNotEmpty) {
        rawData = cachedJson;
      } else {
        rawData = await rootBundle.loadString('assets/channels.json');
      }

      final List<dynamic> decoded = jsonDecode(rawData);
      final channels = decoded.map((item) => Channel.fromJson(item)).toList();

      for (var ch in channels) {
        if (favoriteIds.contains(ch.id)) {
          ch.isFavorite = true;
        }
      }

      return channels;
    } catch (e) {
      if (kDebugMode) {
        print('Kanal yükleme hatası: $e');
      }
      return [];
    }
  }

  /// Arka planda GitHub'dan yeni listeyi kontrol eder ve sessizce günceller
  Future<List<Channel>?> syncWithRemote() async {
    try {
      final response = await http
          .get(Uri.parse(remoteUrl))
          .timeout(const Duration(seconds: 8));

      if (response.statusCode == 200) {
        final prefs = await SharedPreferences.getInstance();
        final currentCache = prefs.getString(_cacheKey);

        // İçerik değişmiş mi kontrol et
        if (currentCache != response.body) {
          await prefs.setString(_cacheKey, response.body);
          final favoriteIds = prefs.getStringList(_favoritesKey) ?? [];

          final List<dynamic> decoded = jsonDecode(response.body);
          final channels =
              decoded.map((item) => Channel.fromJson(item)).toList();

          for (var ch in channels) {
            if (favoriteIds.contains(ch.id)) {
              ch.isFavorite = true;
            }
          }

          return channels;
        }
      }
    } catch (e) {
      if (kDebugMode) {
        print('Remote sync hatası (çevrimdışı veya link yanıt vermedi): $e');
      }
    }
    return null;
  }

  /// Favori durumunu kaydeder
  Future<void> toggleFavorite(Channel channel) async {
    final prefs = await SharedPreferences.getInstance();
    final favoriteIds = prefs.getStringList(_favoritesKey) ?? [];

    channel.isFavorite = !channel.isFavorite;

    if (channel.isFavorite) {
      if (!favoriteIds.contains(channel.id)) {
        favoriteIds.add(channel.id);
      }
    } else {
      favoriteIds.remove(channel.id);
    }

    await prefs.setStringList(_favoritesKey, favoriteIds);
  }

  /// Son izlenen kanalı kaydeder
  Future<void> saveLastChannelId(String channelId) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_lastChannelKey, channelId);
  }

  /// Son izlenen kanalın ID'sini getirir
  Future<String?> getLastChannelId() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_lastChannelKey);
  }
}
