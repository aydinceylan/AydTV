import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'screens/tv_player_screen.dart';
import 'theme/tv_theme.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // TV İçin Ekranı Yatay ve Tam Ekran (Immersive) Yap
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);

  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);

  runApp(const AydTVApp());
}

class AydTVApp extends StatelessWidget {
  const AydTVApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'AydTV',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: TVTheme.background,
        fontFamily: 'Roboto',
        colorScheme: const ColorScheme.dark(
          primary: TVTheme.focusCyan,
          secondary: TVTheme.focusBlue,
          surface: TVTheme.surface,
        ),
      ),
      home: const TVPlayerScreen(),
    );
  }
}
