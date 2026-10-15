import 'package:flutter/material.dart';
import '../theme/tv_theme.dart';

class CastToast extends StatefulWidget {
  final String deviceName;
  final String type; // 'airplay' | 'dlna'
  final VoidCallback onDismissed;

  const CastToast({
    super.key,
    required this.deviceName,
    required this.type,
    required this.onDismissed,
  });

  @override
  State<CastToast> createState() => _CastToastState();
}

class _CastToastState extends State<CastToast> with SingleTickerProviderStateMixin {
  late AnimationController _animController;
  late Animation<double> _fadeAnimation;
  late Animation<Offset> _slideAnimation;

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 400),
    );

    _fadeAnimation = CurvedAnimation(parent: _animController, curve: Curves.easeOut);
    _slideAnimation = Tween<Offset>(
      begin: const Offset(0.2, 0.0),
      end: Offset.zero,
    ).animate(CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic));

    _animController.forward();

    // 4 saniye sonra otomatik kaybol
    Future.delayed(const Duration(seconds: 4), () {
      if (mounted) {
        _animController.reverse().then((_) {
          if (mounted) widget.onDismissed();
        });
      }
    });
  }

  @override
  void dispose() {
    _animController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isAirPlay = widget.type.toLowerCase().contains('airplay');
    return Positioned(
      top: 36,
      right: 48,
      child: FadeTransition(
        opacity: _fadeAnimation,
        child: SlideTransition(
          position: _slideAnimation,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            decoration: BoxDecoration(
              color: const Color(0xF00B101B),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: TVTheme.focusCyan, width: 1.5),
              boxShadow: [
                BoxShadow(
                  color: TVTheme.focusCyan.withValues(alpha: 0.3),
                  blurRadius: 20,
                  spreadRadius: 2,
                ),
              ],
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: TVTheme.focusCyan.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(
                    isAirPlay ? Icons.airplay_rounded : Icons.cast_connected_rounded,
                    color: TVTheme.focusCyan,
                    size: 24,
                  ),
                ),
                const SizedBox(width: 14),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      isAirPlay ? 'AirPlay Ekran Paylaşımı' : 'Smart View / DLNA',
                      style: TextStyle(
                        color: TVTheme.focusCyan.withValues(alpha: 0.9),
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      '📱 "${widget.deviceName}" bağlandı',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 15,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
