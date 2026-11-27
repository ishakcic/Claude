# Claude Sandbox 6X
Çoklu izole WebView profili ile claude.ai oturumları, limit sayaçları, bildirim geçmişi,
yüzen pencere, widget, hızlı ayarlar kutucuğu, Termux terminal köprüsü ve uygulama içi güncelleme.

## GitHub'da derleme
1. Bu klasörün içeriğini (gizli `.github` dahil) PRIVATE bir repoya yükle, dal `main`.
2. Actions > "Build APK" çalışır. Sonunda hem Artifacts'a APK yüklenir hem de Releases'ta `v1.<no>` sürümü oluşur.
3. APK sabit anahtarla (app/release.jks) imzalıdır: yeni sürüm eskisinin üzerine veri kaybetmeden kurulur.

## Uygulama içi güncelleme
Ayarlar > Güncelleme: depo adını (kullanıcı/depo) yaz. Özel depo için yalnızca "Contents: read" yetkili bir GitHub anahtarı gerekir.

## Tasker / MacroDroid
Ayarlar > "Dış uygulama bağlantısı" bölümünde gizli anahtar ve örnek intent'ler var.

## Termux
F-Droid'den Termux kur, Terminal ekranındaki tek seferlik kurulum komutunu çalıştır.
