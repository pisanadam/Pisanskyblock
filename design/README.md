# Pisanskyblock – Tasarım Dokümanı

Hypixel Skyblock'tan ilham alan, Java Edition, 5–10 kişilik arkadaş sunucusu.
Hosting: Exaroton (ücretli).

## 1. Sürüm desteği (1.20.1 → en son)
- Sunucu: **Paper (en son sürüm)**
- **ViaVersion + ViaBackwards**: eski istemcilerin (1.20.1'e kadar) girmesini sağlar.
- Not: Eski sürüm istemcilerde yeni bloklar/eşyalar (ör. en yeni eşyalar) görünmez ya da
  yedek eşya olarak görünür. Özel eşyalar bu yüzden "mevcut eski eşya + CustomModelData/isim"
  ile yapılmalı, yeni eşya türüne bağlı olmamalı.

## 2. Plugin yığını (öneri)
| İş | Plugin | Not |
|---|---|---|
| Skyblock adaları | BentoBox + BSkyBlock | Ücretsiz, bakımı yapılıyor |
| Ekonomi | Vault + EssentialsX (Economy) | Coin birimi |
| Sistem market (NPC) | EconomyShopGUI veya ShopGUIPlus | Fiyat listesi: aşağıda |
| Oyuncu pazarı | Bir "Auction House / Player Market" plugini | İstediğin fiyata ilan, vergi/komisyon opsiyonu |
| Kasalar (crate) | CrazyCrates veya ExcellentCrates | 10 kasa, anahtar sistemi |
| Taş jeneratörü ödülleri | **Küçük özel plugin (ben yazarım)** | BlockBreak → ağırlıklı ödül tablosu |
| Minyonlar | Hazır minyon plugini ya da özel | Ayrı değerlendirilecek |
| İzinler | LuckPerms | |
| Spawn/Hub | WorldGuard + hazır harita | |

Plugin adları ve sürüm uyumluluğu kurulumdan önce tek tek kontrol edilmeli
(güncel Paper'da çalışıyor mu, ücretli mi).

## 3. Market kuralları (sistem marketi)
- Skyblock için gereken temel şeyler satılır: tohumlar, ekinler, toprak/çim, kum, su/lav kovası,
  ağaç fidanları, taş türleri, odun, hayvan yumurtaları (bazıları), alet ve zırh (bkz. alt sınır).
- **Üst sınır:** Elmas eşya düzeyi. Marketten **satılmayanlar:**
  netherite (blok, külçe, eşya, upgrade template), mızrak, trident, elytra, totem,
  beacon, enchanted golden apple, spawner, dragon egg, shulker, ve kasa-özel eşyalar.
- **Fiyat:** Alış fiyatı satış fiyatının ~4 katı olsun (enflasyon ve istismarı önler).
- Nadir şeyler pahalı: elmas eşyalar çok pahalı, ender pearl vb. sınırlı stok.
- Oyuncular marketin sattığı bir şeyi oyuncu pazarında daha ucuza satabilir; bu normal.

## 4. Oyuncu pazarı
- `/ah` veya `/pazar` ile aç.
- Her oyuncu istediği eşyayı istediği fiyata ilana koyar.
- İlan süresi, ilan başına küçük vergi (%2–5, coin sink) ve maksimum ilan sayısı ayarlanabilir.
- Market'te satılmayan eşyalar (netherite vb.) oyuncu pazarında **satılabilir** (oyuncular arası).
  İstersen ilan yasak listesi koyarız.

## 5. Taş jeneratörü
Bkz. `generator.yml` ve `crates.yml`.
- Jeneratörde oluşan **cobblestone/stone kırılırken** bonus ödül rulet'i çalışır.
- Ödül: cevher ya da **anahtar** (10 kademe), değerine göre nadir.
- Ancient debris çok düşük şans (netherite'e giden yolu uzun ve uğraştırıcı yapar).

## 6. Kasalar (10 kademe)
Yeni oyuncu **3 adet Çaylak Anahtarı** ile başlar. Bkz. `crates.yml`.
Üst kasalar, oyunda normalde alınamayan şeyleri (büyük/üst seviye enchant kitapları vb.) verir.

## 7. Yol haritası
1. Faz 1: Paper + Via + BentoBox + Vault + LuckPerms + market + oyuncu pazarı
2. Faz 2: Taş jeneratörü ödül plugini + kasalar
3. Faz 3: Minyonlar, skill/koleksiyon sistemi, pet'ler
4. Faz 4: Boss'lar, etkinlikler, dungeon

## 8. Açık sorular
- Skill/stat (Can, Strength, Defense) sistemi istiyor musun, yoksa vanilla dövüş mü?
- Minyonlar: hazır plugin mi, özel mi?
- Oyuncu pazarı vergisi kaç olsun?
