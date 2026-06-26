# ArkeoSAR Ground Scan

Sıfırdan yazılmış, native Android (Kotlin + OpenGL ES 2.0) tabanlı bir
3D zemin tarama / manyetometre görüntüleme uygulaması. OKM Rover/Scorpion
sınıfı cihazlarla klasik Bluetooth (RFCOMM/SPP) üzerinden konuşur.

## Bu proje neyi referans aldı, neyi almadı

- **Referans alınan:** OKM cihazının donanım iletişim protokolü (SPP UUID
  `00001101-0000-1000-8000-00805F9B34FB`, `0x07` el sıkışma byte'ı, `0x0C`
  sorgu komutu, 3-byte büyük-endian sensör cevabı). Bu, cihazla konuşmak
  için *gereken* arayüz bilgisidir — herhangi bir kontrol uygulamasının
  bilmesi gereken donanım protokolü, OKM'nin yazılımının "yaratıcı ifadesi"
  değildir.
- **Referans alınan:** standart jeofizik tarama kavramları — zigzag
  (boustrophedon) tarama deseni, anomali şiddetine göre renk kodlama,
  GPS-etiketli grid noktaları. Bunlar sektör genelinde kullanılan genel
  yöntemlerdir, herhangi bir şirketin icadı değildir.
- **Referans ALINMAYAN / kullanılmayan:** OKM'nin `com.scr.scorpion`
  paketinin Java kaynak kodu, render algoritmalarının satır satır mantığı,
  ya da `.v3d` ikili dosya formatının byte-seviyesi düzeni. Bu uygulama
  kendi dosya formatını (`.asgs`, düz JSON) kullanır.

## 3D görselleştirme: ArkeoMag / Thuban Lodestar referansı

`ScanActivity`'nin 3D görünümü, kendi ArkeoMag / Thuban Lodestar
projenin "İnce Plaka" yüzey moduna referansla yeniden tasarlandı:

- **Thin Plate Spline interpolasyonu** (`ThinPlateSpline.kt`) — seyrek
  tarama noktalarından pürüzsüz, sürekli bir yüzey üretir (artık köşeli
  grid hücreleri yok). Standart, yayınlanmış bir nümerik yöntem
  (Duchon, 1977); implementasyon ArkeoSAR için sıfırdan yazıldı.
- **Per-vertex normal + ışıklandırma** — yüzey artık düzgün shading
  ile "kumaş gibi gerilmiş" görünüyor, düz renkli facet'ler değil.
- **Fonksiyon seçici** (`DisplayFunction.kt`) — d(X), d(Y), d(Z),
  d(XY), d(YZ), d(XZ), d(XYZ) arasında geçiş; dahili sensörden gelen
  ham X/Y/Z eksenleri saklanır, fonksiyon değişince yeniden taramaya
  gerek kalmadan anlık güncellenir. Not: Bluetooth probe şu an tek
  kanal okuduğu için bu probe'la taranan noktalarda fonksiyon
  değişikliği etkisiz kalır (ham eksen yok).
- **Eşik (threshold) filtresi** — sadece en yüksek anomali bandını
  göstermek için düşük değerli noktaları yüzeyden çıkarır.
- **Renk skalası (colorbar)** — sol altta, değer aralığı + her bandın
  yüzdelik dağılımını gösteren özel `ColorbarView`.
- **Araç çubukları** — sağ üst: ölçüm/görünüm modu/zoom sıfırlama/ekran
  görüntüsü; sol: ızgara/tel-kafes/yüzey/nokta-bulutu geçişi + ayarlar
  paneli aç/kapa.
- **Ekran görüntüsü** — `glReadPixels` ile GPU'dan kare okuyup
  `Pictures/ArkeoSARGroundScan/` altına PNG olarak kaydeder.

## Veri kaynağı: Bluetooth cihaz + dahili sensör fallback

`ScanActivity` artık iki veri kaynağını ortak bir arayüz
(`ScanDataSource`) üzerinden yönetiyor:

1. Önce **Bluetooth probe** (`BluetoothDataSource`, OKM Rover-class
   cihaz) denenir.
2. Eşleştirilmiş cihaz bulunamazsa, bağlantı hata verirse, veya
   6 saniye içinde `ACTIVE` durumuna geçmezse, otomatik olarak
   **telefonun dahili manyetometresine** (`InternalSensorSource`,
   `Sensor.TYPE_MAGNETIC_FIELD`) geçilir.

Durum çubuğunda her zaman hangi kaynağın aktif olduğu ("Harici Cihaz"
/ "Dahili Sensör") gösterilir. Dahili sensör modunda:

- Okunan değer, 3 eksenli manyetik alan vektörünün büyüklüğüdür
  (mikrotesla). Bu, herhangi bir telefon manyetometresini tek bir
  anomali-şiddeti değerine çevirmenin standart yoludur.
- Fiziksel bir tetik butonu olmadığı için bu mod her zaman "otomatik"
  gibi çalışır.
- Telefonun kendi manyetometresi, hoparlör mıknatısı gibi iç bileşenlerden
  etkilenebilir ve özel bir prob kadar hassas değildir — bu, gerçek bir
  donanım kısıtı, yazılım hatası değil.
- Konum izni verilmişse GPS koordinatları da (varsa) her ölçüme eklenir.

## Görünüm modu anahtarı: 2D / 3D Yüzey / 3D Hacimsel

`ScanActivity`'nin üst kısmına üç sekmeli bir görünüm anahtarı eklendi.
Bu ekran ve davranışları, Hasan'ın kendi ekran kaydından (Thuban
Lodestar uygulamasının "Yüzey Ayarları" / "Hacim Ayarları" panelleri)
doğrudan incelenerek netleştirildi:

- **2D**: Aynı yüzey verisi, ama gerçek geometri düzleştiriliyor
  (Y ekseni sabitleniyor) - kamera açısı değil, mesh'in kendisi
  düzleşip referans kutusunun içinde duruyor (incelenen ekran
  kaydındaki 2D/3D anahtarıyla aynı davranış).
- **3D Yüzey**: TPS-interpolasyonlu, ışıklandırılmış yüzey (değişmedi).
- **3D Hacimsel**: `VolumetricMesh` + `MarchingSquares` ile üretilen,
  organik "sarkıt" benzeri bir izosurface - aşağıda detaylandırılıyor.

### Hacimsel görünüm: Marching Squares tabanlı izosurface

İlk sürümde düz, üst üste dilimler halinde bir blok vardı; incelenen
ekran kaydında görünenin (yukarıdan aşağı sarkan, daralan organik
şekiller) çok farklı olduğu görüldü. Bunun üzerine yaklaşım değiştirildi:

- `MarchingSquares.kt`: her derinlik dilimi için, [SchematicDepthModel]'in
  o derinlikteki şiddet alanından bir eşik konturu çıkarır (standart,
  onlarca yıllık bir bilgisayar grafiği algoritması - Marching Cubes'un
  2D karşılığı).
- `VolumetricMesh.kt`: ardışık dilimlerin konturlarını en-yakın-segment
  eşlemesiyle birbirine bağlayan yan duvar quad'ları üretir, böylece
  yığın tek parça, daralan bir şekil gibi görünür (düz kartlar üst üste
  değil).
- Bu, tam bir 3D Marching Cubes değil - dilim bazlı, basitleştirilmiş
  bir izosurface yaklaşımı; mobil GLES 2.0'da görsel doğruluk/performans
  dengesini tutmak için tercih edildi.
- Eşik değeri, "Eşik" slider'ı ile gerçek zamanlı ayarlanabilir (yüzey
  görünümündeki eşik filtresiyle aynı slider, `renderer.volumetricThreshold`).

### Şematik derinlik modeli - önemli bilimsel sınır

3D Hacimsel görünüm, **gerçek bir derinlik ölçümüne dayanmıyor** -
tek bir yüzey manyetometre taraması, gömülü bir kaynağın derinliğini
kesin olarak belirleyemez (aynı yüzey deseni, farklı derinlik+şiddet
kombinasyonlarından üretilebilir; bu "ill-posed" bir ters problemdir).

`SchematicDepthModel.kt`, gerçek bir fizik prensibine (manyetik
dipolün alanı kaynaktan mesafenin küpüyle ters orantılı azalır,
B ∝ 1/r³) dayanarak, **varsayılan bir kaynak derinliği** (şu an sabit
`DEFAULT_ASSUMED_DEPTH`) etrafında yüzey okumalarını aşağı doğru
projekte ediyor. Bu, "kaynak tipik bir dipol gibi davranıyorsa derinlikte
böyle görünür" şeklinde bir **model varsayımı**, ölçülmüş ya da
ters-çözümlenmiş bir derinlik değeri değil.

Bu yüzden 3D Hacimsel modda ekranda her zaman görünür bir uyarı bandı
var: "⚠ Şematik derinlik modeli — gerçek ölçüm değildir, tahminidir".
Bu uyarı kasıtlı olarak kalıcı/göz ardı edilemez tutuldu (sadece açılışta
bir kerelik bilgi notu değil) çünkü kullanıcı bu görünümü sahada gerçek
bir derinlik ölçümüyle karıştırabilir.

İncelenen ekran kaydında bu hesaplama "Toprak Ayarları" altında toprak
tipine (Tuz, Kil, Kum, Granit, Yüksek Mineral, vb. - 14 seçenek) göre
ayarlanabiliyordu ve gerçek bir "Derinlik: 1.94 m" değeri gösteriyordu.
Bu toprak-tipi entegrasyonu henüz eklenmedi (bkz. sınırlamalar bölümü).

## Ana menüde "GPR" başlığı: birleşik tara → görüntüle → analiz akışı

Ana menüye ayrı bir **GPR** bölümü ve **"GPR Taraması"** butonu eklendi.
Bu, var olan tarama/görüntüleme/analiz bileşenlerini (`GridScanActivity`,
`ScanActivity`, B-Scan, Hacimsel görünüm, Dewow, Stacking, CSV dışa
aktarma) **GPR çerçevesiyle etiketlenmiş, tek bir akış** olarak sunan
ince bir katman - ayrı bir kod tabanı veya gerçek bir GPR simülasyonu
değil:

1. "GPR Taraması" → Otomatik/Manuel mod seçimi (aynı diyalog, `gprMode`
   bayrağıyla işaretlenmiş).
2. Tarama ekranı (`GridScanActivity`), üstte kalıcı bir GPR uyarı
   bandıyla açılır: "Bu özellik dahili manyetometre verisini GPR analiz
   teknikleriyle işler. Gerçek bir GPR cihazı değildir..."
3. Tarama bitince otomatik olarak `ScanActivity`'ye geçilir - **Hacimsel
   sekmesi seçili ve Enterpolasyon Ayarları paneli (Fonksiyon/Renk
   Skalası/Eşik/Dewow/CSV) doğrudan açık** olarak, çünkü GPR akışının
   amacı "tara, sonuca hemen analiz uygula."
4. Aynı uyarı bandı (`schematicDisclaimer`), GPR modunda **her sekmede**
   görünür kalır - sadece Hacimsel modda değil, çünkü tüm GPR-çerçeveli
   akış bu bilimsel sınırı taşıyor.

**Neden ayrı bir "gerçek GPR" sistemi kurulmadı:** Bir GPR, darbe
gönderip yankı dinleyerek gerçek zaman-derinlik ölçümü yapar; bunun
donanım tarafı (anten, pulser, T/R anahtarı, RF amplifikatör) telefonun
manyetometresinde hiçbir karşılığı olmayan, tamamen farklı bir fiziksel
sistemdir. "GPR" başlığı burada bir **sunum çerçevesi** - kullanıcının
tanıdığı isim ve iş akışı - ama her ekranda açık bir dille bunun gerçek
bir radar ölçümü olmadığı belirtiliyor.

## GPR tarzı sinyal işleme: Stacking + Dewow

GPR (yer radarı) donanım/işleme literatüründen iki standart, **veri
işleme** tekniği (donanım gerektirmeyen, sadece matematiksel adımlar)
manyetometre verisine uyarlandı:

- **Stacking** (`StackingAccumulator.kt`): Manuel tarama modunda her
  "Ölç" basışında artık **tek bir anlık ölçüm değil**, kısa bir
  patlama halinde toplanan birkaç örneğin (varsayılan 8) **ortalaması**
  ızgaraya yazılıyor. Rastgele sensör gürültüsü ortalamada birbirini
  götürürken gerçek sinyal kalır - GPR cihazlarının "coherent averaging"
  ile sinyal/gürültü oranını iyileştirmesiyle aynı mantık. Buton metni
  ölçüm sırasında "Ölçülüyor… N/8" gösterir.
- **Dewow** (`DewowFilter.kt`): `ScanActivity`'deki "Dewow Filtresi
  Uygula" anahtarı, tamamlanmış bir taramanın her satırı/sütunu boyunca
  hareketli ortalama tabanlı bir taban çizgisi çıkarır - GPR
  yazılımlarının ham izleri işlemeden önce uyguladığı standart bir ön
  işleme adımı. Geri alınabilir: anahtar kapatılınca orijinal değerler
  geri yüklenir (`preDewowValues` yedeği).

**Kapsam dışı bırakılanlar (ve neden):** Gönderilen GPR donanım kontrol
listesindeki çoğu madde (anten tasarımı, darbe üreteci, T/R anahtarı,
RF güç amplifikatörü, dinamik aralık/lineerlik) **fiziksel bir radar
vericisine özel** - bunların manyetometre tarafında bir karşılığı yok,
çünkü manyetometre darbe göndermez/yankı dinlemez. Bu yüzden bu proje
kapsamına alınmadı; bkz. B-Scan bölümündeki ilgili tartışma.

## B-Scan kesit görünümü ("GPR gibi" sunum - gerçek GPR değil)

`ScanActivity`'nin sol araç çubuğuna yeni bir ikon eklendi: mevcut
taramayı `BScanActivity`'de, klasik GPR (yer radarı) yazılımlarının
kullandığı **B-Scan** görsel diliyle (yatay eksen = konum, dikey eksen
= derinlik, renk = sinyal şiddeti) gösteriyor. Kayıtlı Taramalar
ekranında bir dosyaya **uzun basarak** da aynı görünüme gidilebilir
(normal dokunma hâlâ 3D görünümü açar).

**Bu gerçek bir GPR verisi değil ve öyle sunulmuyor.** Telefonun
manyetometresi statik bir manyetik alanı ölçer; bir verici darbesi
yok, bir alıcı anten yok, gidiş-dönüş süresi (time-of-flight) ölçümü
yok - gerçek bir GPR'ın derinliği bu şekilde hesaplar. `BScanView.kt`
sadece manyetometre okumalarını, zaten var olan
`SchematicDepthModel`'in şematik derinlik projeksiyonuyla, GPR
kullanıcılarının okumaya alışık olduğu **kesit düzeninde** gösteriyor.
Gömülü bir nesnenin gerçek GPR B-Scan'inde oluşturduğu karakteristik
"hiperbol" şekli **kasıtlı olarak simüle edilmedi** - bu şekil, bu
uygulamanın ölçemediği bir geometriden (zamanlama) kaynaklanır;
taklit etmek veri uydurmak olurdu. Ekranda her zaman görünür, kalıcı
bir uyarı bandı var: "Bu bir GPR verisi değildir... şematik bir model
ile tahmin edilmiştir."

## Manyetometre kalibrasyonu (hard-iron)

Ana menüye "Kalibrasyon" butonu eklendi, `CalibrationActivity`:

- Üç canlı serpme grafiği (XY, YZ, XZ) - referans uygulamanın
  "Kalibrasyon Modu" ekranındaki gibi, telefon döndürülürken ham
  X/Y/Z örnekleri her grafikte ilgili eksen çiftine işaretleniyor.
- Swipe-to-start kontrolü ("Başlat tuşunu sağa kaydırın").
- `MagnetometerCalibration.kt`: standart bir manyetometre kalibrasyon
  tekniği olan **hard-iron bias düzeltmesi** - telefon her eksende
  döndürülürken toplanan min/max değerlerinin ortası, sabit bir
  ofset (bias) olarak hesaplanır ve SharedPreferences'a kaydedilir.
  `InternalSensorSource` her okumadan bu bias'ı çıkarır - kalibrasyon
  çalıştırılmamışsa bias sıfır olduğu için bu adım isteğe bağlıdır,
  zorunlu değildir.
- **Kapsam notu:** Bu sadece sabit (hard-iron) sapmayı düzeltir;
  soft-iron distorsiyonu (eksenleri ölçekleyen/çarpıtan etki) için tam
  bir elipsoid fit gerekir ve henüz eklenmedi - "Bilinen sınırlamalar"
  bölümüne not edildi.

## Tarama alanı kurulumu: genişlik/uzunluk + başlangıç yönü

"3 Boyutlu Zemin Tarama" / "GPR Taraması" butonuna basıp Otomatik/Manuel
seçimini yapınca, ızgaraya geçmeden önce **`ScanAreaSetupActivity`**
açılıyor:

- **Genişlik (m)** ve **Uzunluk (m)** girişi - ızgaranın satır/sütun
  sayısı bu ikisinden **bağımsız** hesaplanıyor (`ScanGrid.resolutionForMeters`
  her eksen için ayrı çağrılıyor), yani 8m × 3m gibi dar-uzun bir alan,
  ızgarayı da dar-uzun (yatay dikdörtgen) şekilde oluşturuyor - kare
  alan kare ızgara, dikdörtgen alan dikdörtgen ızgara üretir.
- **Taramaya Başlama Yönü: Soldan / Sağdan** - `ScanGrid`'in yeni
  `startFromOpposite` parametresi, zigzag deseninin ilk hattının hangi
  taraftan başlayacağını belirliyor (sonraki hatlar normal şekilde
  alternatif yönde devam ediyor).
- Canlı bir "Izgara: N × M kare" önizlemesi, girilen değerlere göre
  güncelleniyor.

## 2D ızgara dedektör ekranı: Otomatik / Manuel mod + otomatik 3D geçiş

`GridScanActivity`, ArkeoMag / Thuban Lodestar'ın hücre-hücre tarama
ekranına referansla eklendi.

- **Otomatik**: aktif veri kaynağından (Bluetooth probe ya da dahili
  sensör) gelen her okuma anında ızgaraya yazılır - sürekli yürüyerek
  tarama tarzı.
- **Manuel** (`ScanTriggerMode.MANUAL`): okumalar sürekli akar ve anlık
  değer ekranda güncellenir, ama ızgaraya hiçbir şey yazılmaz - "Ölç"
  butonuna her basışta kısa bir stacking (8 örnek ortalaması, bkz. GPR
  sinyal işleme bölümü) yapılıp sonuç aktif hücreye kaydedilir ve bir
  sonraki hücreye geçilir. Cihazı her noktada elle konumlandırıp tek
  tek ölçüm almak isteyen taramalar için.

Ortak davranışlar (her iki modda da):

- Sütun-bazlı zigzag tarama (`ScanAxis.COLUMN_MAJOR`), seçilen
  başlangıç yönüyle.
- **Henüz ölçülmemiş hücreler tamamen boş/karanlık kalır** - canlı bir
  interpolasyon önizlemesi gösterilmez; ızgara sadece gerçekten ölçülmüş
  hücreleri renklendirir, böylece "her adımda bir kare doluyor"
  davranışı net görülür.
- Aktif hücre sarı ok ile vurgulanıyor, ok yönü zigzag'ın hangi tarafta
  olduğuna göre değişiyor.
- Izgara tamamlanınca "Lütfen Bekleyiniz…" mesajıyla otomatik olarak
  `ScanActivity`'e geçiyor - **doğrudan 3D Hacimsel sekmesi seçili
  olarak** (`EXTRA_INITIAL_VIEW_MODE`), taranan verinin hemen
  derinlik/hacim analizine geçilebilmesi için. Toplanan veri ara bir
  dosyaya kaydedilip oradan yükleniyor.

## Renk skalaları, CSV dışa aktarma, vektörel analiz

- **10 renk skalası** (`ColorPalette.kt` içinde `AnomalyColorScale`'e
  eklendi): Chlorophy (varsayılan), Autumn, Gray, Bone, Jet, Winter,
  Summer, Spring, Hot, Cool - bu isimler ve renk tarifleri Hasan'ın
  ekran kaydında incelenen uygulamanın "Grafik Renkleri" listesinden
  alındı. Bunlar MATLAB/matplotlib'in onlarca yıldır kullanılan
  standart colormap aile isimleri (jet, hot, cool, bone, autumn,
  winter, spring, summer, gray) - genel, yaygın yayınlanmış renk-durağı
  tarifleridir, kimsenin tescilli varlığı değildir. `ScanActivity`'deki
  Enterpolasyon Ayarları panelinde "Renk Skalası" seçicisinden
  değiştirilebilir; seçim anlık olarak 3D yüzeye ve colorbar'a yansır.
- **CSV dışa aktarma** (`CsvExporter.kt`): "CSV Olarak Dışa Aktar"
  butonuyla taramanın tamamı (row, col, value, GPS, ham X/Y/Z) düz CSV
  olarak `Documents/ArkeoSARGroundScan/` altına kaydedilir - Excel,
  pandas, R gibi araçlarda açılabilir.
- **3 eksenli vektörel analiz**: Ayrı bir ekran eklenmedi - zaten var
  olan "Fonksiyon" seçici (d(X), d(Y), d(Z), d(XY), d(YZ), d(XZ),
  d(XYZ)) bu işlevi görüyor; dahili sensörden gelen ham X/Y/Z eksenleri
  zaten saklanıyor (bkz. `ScanGrid.recomputeWithFunction`).

## Proje yapısı

```
app/src/main/java/com/arkeosar/groundscan/
  ui/          MainActivity, ScanActivity, GridScanActivity, ScanAreaSetupActivity,
               CalibrationActivity, BScanActivity, SettingsActivity, FileExplorerActivity,
               ColorbarView, GridScanView, CalibrationGraphView, BScanView
  data/        ScanGrid (ScanAxis: row/column-major), ScanTriggerMode (otomatik/manuel),
               DewowFilter, StackingAccumulator, ArkeoSarFile, CsvExporter, SettingsData,
               ScanDataSource (ortak arayüz)
  bluetooth/   BluetoothScanService (ham RFCOMM), BluetoothDataSource (adaptör)
  sensors/     InternalSensorSource (telefonun dahili manyetometresi),
               MagnetometerCalibration (hard-iron bias düzeltmesi)
  render/      HeightmapRenderer, HeightmapMesh, VolumetricMesh, MarchingSquares,
               ThinPlateSpline, SchematicDepthModel, AnomalyColorScale, ColorPalette,
               DisplayFunction, RenderMode, ViewMode (OpenGL ES 2.0)
app/src/main/assets/shaders/   heightmap.vert/.frag (lit surface), volumetric.vert/.frag (unlit, alpha-blended)
```

## Derleme

Bu ortamda (sandbox) Android SDK ve Gradle dağıtımına ağ erişimi
olmadığı için APK burada derlenemedi. Projeyi kendi makinende veya
Android Studio'da derlemek için:

1. Bu klasörü Android Studio ile aç (`File > Open`, klasörün kökünü seç).
2. Studio otomatik olarak Gradle senkronizasyonunu yapacak ve eksik SDK
   bileşenlerini (compileSdk 34, build-tools) indirecektir.
3. `Run` (▶) tuşuna bas, ya da terminalden:
   ```
   ./gradlew assembleDebug
   ```
   Çıktı: `app/build/outputs/apk/debug/app-debug.apk`

Veya GitHub Actions ile: bu repoya push ettiğinde `.github/workflows/build.yml`
otomatik olarak debug APK'yı derler; sonucu Actions sekmesinin
Artifacts kısmından indirebilirsin.

## Bilinen sınırlamalar / sonraki adımlar

- **B-Scan keşfedilebilirlik:** Kayıtlı Taramalar ekranında B-Scan'a
  geçiş şu an bir dosyaya **uzun basarak** yapılıyor - bu, normal
  dokunmadan ayırt edilmesi kolay olmayan, düşük keşfedilebilirlikte
  bir etkileşim. İleride bir bağlam menüsü (context menu) ya da görünür
  bir ikon ile değiştirilebilir.

- **Yapıldı:** Şematik derinlik modeli + Marching-Squares tabanlı 3D
  hacimsel görünüm (bkz. yukarıdaki bölüm) - ama varsayılan derinlik şu
  an sabit bir sabit (`SchematicDepthModel.DEFAULT_ASSUMED_DEPTH`),
  kullanıcı arayüzünden henüz ayarlanamıyor.
- **Marching Squares performans notu:** Kontur çıkarma + dilimler arası
  en-yakın-segment eşlemesi, her dilimde ham `ScanGrid` çözünürlüğü
  üzerinde çalışıyor (interpolasyonlu yoğun mesh değil) - büyük
  taramalarda (çok dolu nokta) yavaşlarsa, dilim sayısını
  (`depthSliceCount`) düşürmek ilk çözüm olur.
- **Yapıldı:** Manyetometre kalibrasyonu (hard-iron bias, bkz. yukarıdaki
  bölüm) - ama sadece sabit ofset düzeltiyor; soft-iron (eksen
  ölçekleme/çarpıtma) düzeltmesi için tam elipsoid fit gerekir, henüz yok.
  Ayrıca kalibrasyon ekranında henüz "Kalibrasyonu Sıfırla" butonu yok
  (`MagnetometerCalibration.clear()` zaten var, sadece UI'a bağlanmadı).
- **Henüz eklenmedi (istek listesinde, sırayla planlanıyor):**
  9 farklı enterpolasyon yöntemi (şu an sadece İnce Plaka/TPS var -
  incelenen ekran kaydında NearestNeighbor, Linear, Cubic2, Cubic4,
  BSpline, Sinc5, Gaussian2, Gaussian4, Cosine listelendi), tarama
  modu seçimi (Manuel / Izgara / Paralel + boyut ayarları), artırılmış
  gerçeklik (AR) destekli görselleştirme, gelişmiş manyetometre
  kalibrasyonu (XY/YZ/XZ elips fit ekranı), toprak tipine göre derinlik
  katsayısı (incelenen kayıtta 14 toprak tipi - Nötr, Beton, Mil, Kum,
  Kil, Hafif/Yüksek Mineral, Taşlı, Tatlı/Tuzlu Su, Kar, Donmuş Toprak,
  Kömür, Granit - "Derinlik: 1.94 m" gibi gerçek bir değer üretiyordu),
  varsayılan derinlik parametresinin arayüzden ayarlanabilmesi.

- **Ölçüm aracı (cetvel ikonu):** Şu an sadece bir bildirim gösteriyor;
  yüzey üzerinde iki nokta seçip gerçek-dünya birimleriyle mesafe
  okuma henüz uygulanmadı.
- **GRID render modu:** Şu an SURFACE ile aynı çiziliyor (ayrı bir
  "ızgara çizgileri üstte" overlay'i yok); WIREFRAME modu zaten ayrı
  bir görünüm sağlıyor.
- **TPS performansı:** Çok büyük taramalarda (yüzlerce dolu nokta)
  interpolasyon yavaşlayabilir - bkz. `ThinPlateSpline.kt` içindeki
  performans notu.
- **Fonksiyon seçici / Bluetooth probe:** Harici cihazdan gelen
  okumalar tek kanallı olduğu için (ham X/Y/Z yok), bu noktalarda
  fonksiyon değişikliği uygulanmaz - sadece dahili sensör modunda
  tam çalışır.
- **Cihaz eşleştirme:** `BluetoothScanService.findCandidateDevices()`
  şu an sadece zaten **eşleştirilmiş (bonded)** cihazları tarıyor. Yani
  Rover'ı önce Android'in Bluetooth ayarlarından eşleştirmen gerekiyor.
  Gerekirse `BluetoothAdapter.startDiscovery()` + `BroadcastReceiver`
  ile canlı keşif (discovery) akışı eklenebilir.
- **Thread senkronizasyonu:** `ScanGrid`, UI thread'inden (`onProbeReading`)
  yazılıp GL render thread'inden okunuyor. Şu anki implementasyon basit
  ve pratikte çalışır, ama tam doğruluk için `synchronized` blokları veya
  bir immutable snapshot mekanizması eklenebilir.
- **Manuel mod:** `Settings` ekranındaki "Otomatik Mod" anahtarı şu an
  `ScanActivity`'e henüz bağlanmadı — otomatik mod her zaman aktif.
  Manuel moda geçişte `onProbeReading`'i `buttonPressed` durumuna göre
  gate'lemek gerekir.
- **.v3d içe aktarma:** OKM'nin orijinal yazılımıyla kaydedilmiş eski
  taramaları açmak istersen, ayrı bir `OkmV3dImporter` sınıfı eklenebilir
  (yalnızca okuma; ArkeoSAR'ın kendi formatından bağımsız).
