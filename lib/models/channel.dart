class Channel {
  final String id;
  final String name;
  final String category;
  final String logo;
  final String url;
  final List<String> backupUrls;
  final String? epgId;
  final Map<String, String>? headers;
  String currentProgram;
  bool isFavorite;

  Channel({
    required this.id,
    required this.name,
    required this.category,
    required this.logo,
    required this.url,
    this.backupUrls = const [],
    this.epgId,
    this.headers,
    this.currentProgram = '',
    this.isFavorite = false,
  });

  factory Channel.fromJson(Map<String, dynamic> json) {
    String rawUrl = json['url'] ?? '';
    Map<String, String> parsedHeaders = {};

    if (json['headers'] != null) {
      json['headers'].forEach((k, v) {
        parsedHeaders[k.toString()] = v.toString();
      });
    }

    if (rawUrl.contains('|')) {
      final parts = rawUrl.split('|');
      rawUrl = parts[0];
      for (int i = 1; i < parts.length; i++) {
        final param = parts[i];
        if (param.toLowerCase().startsWith('referer=')) {
          parsedHeaders['Referer'] = param.substring(8);
        } else if (param.toLowerCase().startsWith('user-agent=')) {
          parsedHeaders['User-Agent'] = param.substring(11);
        }
      }
    }

    final cat = json['category'] ?? 'Genel';
    final name = json['name'] ?? 'Kanal';

    List<String> parsedBackups = [];
    if (json['backupUrls'] != null && json['backupUrls'] is List) {
      parsedBackups = (json['backupUrls'] as List)
          .map((e) => e.toString().trim())
          .where((e) => e.isNotEmpty)
          .toList();
    }

    // Başlangıç EPG bilgisi (TV+ EpgService arka planda gerçek veriyi çeker)
    String epg = json['epg'] ?? '';
    if (epg.isEmpty) {
      if (cat.toLowerCase().contains('haber')) {
        epg = 'Günün Gelişmeleri & Canlı Yayın';
      } else if (cat.toLowerCase().contains('spor')) {
        epg = 'Spor Bülteni & Canlı Yayın';
      } else if (cat.toLowerCase().contains('çocuk')) {
        epg = 'Çizgi Dizi Kuşağı';
      } else if (cat.toLowerCase().contains('müzik')) {
        epg = 'Kesintisiz Hit Müzik';
      } else if (cat.toLowerCase().contains('yerel')) {
        epg = 'Bölgesel Haber & Yayın';
      } else {
        epg = '$name Canlı Yayın';
      }
    }

    return Channel(
      id: json['id'] ?? rawUrl.hashCode.toString(),
      name: name,
      category: cat,
      logo: json['logo'] ?? '',
      url: rawUrl.trim(),
      backupUrls: parsedBackups,
      epgId: json['epgId']?.toString(),
      headers: parsedHeaders.isNotEmpty ? parsedHeaders : null,
      currentProgram: epg,
      isFavorite: json['isFavorite'] == true,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'category': category,
      'logo': logo,
      'url': url,
      'backupUrls': backupUrls,
      'epgId': epgId,
      'headers': headers,
      'isFavorite': isFavorite,
      'epg': currentProgram,
    };
  }
}
