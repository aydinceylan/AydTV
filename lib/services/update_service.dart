import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:ota_update/ota_update.dart';

class UpdateInfo {
  final String version;
  final String downloadUrl;
  final String releaseNotes;

  UpdateInfo({
    required this.version,
    required this.downloadUrl,
    required this.releaseNotes,
  });
}

class UpdateService {
  static const String currentVersion = '1.0.0';
  static const String githubApiUrl =
      'https://api.github.com/repos/aydinceylan/AydTv_App/releases/latest';

  /// GitHub Releases üzerinden yeni APK olup olmadığını kontrol eder
  Future<UpdateInfo?> checkForUpdate() async {
    try {
      final response = await http.get(
        Uri.parse(githubApiUrl),
        headers: {'Accept': 'application/vnd.github.v3+json'},
      ).timeout(const Duration(seconds: 6));

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        final tagName = (data['tag_name'] ?? '').toString().replaceAll('v', '');
        final body = data['body'] ?? '';

        if (_isNewerVersion(tagName, currentVersion)) {
          final assets = data['assets'] as List<dynamic>?;
          if (assets != null) {
            final apkAsset = assets.firstWhere(
              (a) => (a['name'] as String).toLowerCase().endsWith('.apk'),
              orElse: () => null,
            );

            if (apkAsset != null) {
              return UpdateInfo(
                version: tagName,
                downloadUrl: apkAsset['browser_download_url'] ?? '',
                releaseNotes: body,
              );
            }
          }
        }
      }
    } catch (e) {
      if (kDebugMode) {
        print('Güncelleme kontrol hatası: $e');
      }
    }
    return null;
  }

  /// Yeni APK indirme ve kurulum sürecini başlatır
  Stream<OtaEvent> startDownloadAndInstall(String apkUrl) {
    return OtaUpdate().execute(
      apkUrl,
      destinationFilename: 'aydtv-latest.apk',
      androidProviderAuthority: 'com.aydtv.aydtv.provider',
    );
  }

  bool _isNewerVersion(String remote, String current) {
    if (remote.isEmpty) return false;
    final rParts = remote.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    final cParts = current.split('.').map((e) => int.tryParse(e) ?? 0).toList();

    for (int i = 0; i < rParts.length && i < cParts.length; i++) {
      if (rParts[i] > cParts[i]) return true;
      if (rParts[i] < cParts[i]) return false;
    }
    return rParts.length > cParts.length;
  }
}
