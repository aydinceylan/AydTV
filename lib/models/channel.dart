class Channel {
  final String id;
  final String name;
  final String category;
  final String logo;
  final String url;
  final Map<String, String>? headers;
  final String currentProgram;
  final double programProgress;
  bool isFavorite;

  Channel({
    required this.id,
    required this.name,
    required this.category,
    required this.logo,
    required this.url,
    this.headers,
    this.currentProgram = '',
    this.programProgress = 0.5,
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
    
    // Gerçekçi EPG (Yayın Akışı) Başlığı Üretici
    String epg = json['epg'] ?? '';
    if (epg.isEmpty) {
      if (cat.toLowerCase().contains('haber')) {
        epg = 'Ana Haber Bülteni • Canlı';
      } else if (cat.toLowerCase().contains('spor')) {
        epg = 'Canlı Maç & Spor Özel';
      } else if (cat.toLowerCase().contains('çocuk')) {
        epg = 'Çizgi Dizi Kuşağı';
      } else if (cat.toLowerCase().contains('müzik')) {
        epg = 'Top 20 Hit Müzik';
      } else if (cat.toLowerCase().contains('yerel')) {
        epg = 'Günün Gelişmeleri';
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
      headers: parsedHeaders.isNotEmpty ? parsedHeaders : null,
      currentProgram: epg,
      programProgress: 0.45,
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
      'headers': headers,
      'isFavorite': isFavorite,
      'epg': currentProgram,
    };
  }
}
