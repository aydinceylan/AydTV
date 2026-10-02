class Channel {
  final String id;
  final String name;
  final String category;
  final String logo;
  final String url;
  final Map<String, String>? headers;
  bool isFavorite;

  Channel({
    required this.id,
    required this.name,
    required this.category,
    required this.logo,
    required this.url,
    this.headers,
    this.isFavorite = false,
  });

  factory Channel.fromJson(Map<String, dynamic> json) {
    // Özel referer / user-agent link desteği (|referer=...&|user-agent=...)
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

    return Channel(
      id: json['id'] ?? rawUrl.hashCode.toString(),
      name: json['name'] ?? 'Kanal',
      category: json['category'] ?? 'Genel',
      logo: json['logo'] ?? '',
      url: rawUrl.trim(),
      headers: parsedHeaders.isNotEmpty ? parsedHeaders : null,
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
    };
  }
}
