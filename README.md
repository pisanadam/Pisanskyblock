# Pisanskyblock

Hypixel Skyblock'tan ilham alan Java skyblock sunucusu (Exaroton, Paper 26.3, 1.20.1+ istemci desteği).

## Klasörler
- `pisancore/` – Sunucuya özel plugin (Maven): coin ekonomisi (Vault), taş jeneratörü ödülleri, 10 kademe kasa sistemi.
- `server-config/` – Sunucuya yüklenen düzenlenmiş config dosyaları (market, pazar, BentoBox).
- `design/` – Tasarım dokümanı.
- `pisangames/` – **Pisan Games lobisi** plugini (Exaroton `Pisangames` sunucusu, Paper 26.3). Pusula menüsü veya plakalarla PisanPvP / Pisanskyblock / Pisan SF League / Pisan Strike'a transfer eder; hedef sunucu kapalıysa Exaroton API ile başlatıp açılınca otomatik gönderir.
- `tools/` – Yardımcı scriptler (`exa.sh`: Exaroton API, `EXAROTON_TOKEN` ortam değişkeni ister).

## Sunucudaki pluginler
ViaVersion + ViaBackwards, BentoBox + BSkyBlock, LuckPerms, VaultUnlocked, PlaceholderAPI,
EconomyShopGUI (market), AuctionHouse (oyuncu pazarı), GrimAC (anti-hile), PisanCore.

Ada koruma alanı: 500x500 (protection-range 250).

## Oyuncu komutları
| Komut | İş |
|---|---|
| `/is` | Ada menüsü (ilk girişte ada otomatik açılır) |
| `/shop` | Sistem market |
| `/market <eşya>` | O eşyanın satın alma ekranını direkt açar (ör. `/market elmas`, `/market hopper`, `/market efficiency`) |
| `/ah` | Oyuncu pazarı (`/ah sell <fiyat>` elindekini satışa koyar) |
| `/kasa` | Kasa menüsü (sol tık aç, sağ tık içerik) |
| `/kasa anahtar` | Anahtarlarını listeler |
| `/duel <oyuncu>` | Eşyalı duel isteği (`/duel kabul`, `/duel red`, `/duel pes`). Kazanan kaybedenin tüm eşyalarını alır |
| `/takas <oyuncu>` | Takas isteği (`/takas kabul`, `/takas red`). Eşya + Coin, iki taraf onaylayınca 3 sn sonra tamamlanır |
| `/minyon` | Minyon marketi. Minyonu adanda bir bloğa sağ tıklayarak koy, sağ tıkla menüsünü aç (topla / yükselt / al) |
| `/skill [oyuncu]` | Skill menüsü (Madencilik, Çiftçilik, Odunculuk, Savaş, Balıkçılık) |
| `/siralama [para\|ada\|skill]` | Sıralamalar |
| `/is level`, `/is top` | Ada puanını hesapla / ada sıralaması (BentoBox Level) |
| `/sb` | Scoreboard aç/kapat |
| `/arkadas` | Arkadaş menüsü (`ekle`, `kabul`, `red`, `git`, `sil`, `liste`, `izinler`). Sol tık: adasına git, sağ tık: senin adandaki yetkisi |
| `/lobi` (`/spawn`) | Lobiye ışınlan. Lobideki kasalara sol tık: anahtarla aç, sağ tık: içerik ve şanslar |
| `/bal`, `/pay <oyuncu> <miktar>`, `/baltop` | Coin |

## Admin komutları
`/kasa ver <oyuncu> <kasa> [adet]`, `/minyon ver <oyuncu> <tür> [seviye] [adet]`, `/lobi spawnayarla|yenidenkur|kasaayarla <kasa>`, `/eco give|take|set <oyuncu> <miktar>`, `/pisancore reload`

## PisanCore derleme
```
cd pisancore && mvn package   # target/PisanCore-1.0.0.jar
```
Ayarlar: `plugins/PisanCore/config.yml` (jeneratör şansları), `plugins/PisanCore/crates.yml` (kasa içerikleri).

## Pisan Games lobisi (`Pisangames.exaroton.me`)
Oyuncu lobiye girer → pusula (`/oyun`) veya lobideki 4 plakadan birini seçer → sunucu uyuyorsa başlatılır (bossbar ile ilerleme), açılınca 1.20.5+ istemciler transfer paketiyle otomatik geçer. Daha eski istemcilere adres tıkla-kopyala olarak verilir.

Komutlar: `/oyun [pvp|skyblock|sfleague|strike]`, `/pvp`, `/skyblock`, `/sfleague`, `/strike`, `/lobi`. Admin: `/pisangames reload|setspawn|rebuild`.

Kurulum: `cd pisangames && mvn package`, jar'ı `plugins/` içine at. `plugins/PisanGames/config.yml` içinde `exaroton-token` ayarla (git'e koyma). Hedef sunucularda transfer açık olmalı: Paper'da `accepts-transfers=true`, Velocity'de `accepts-transfers = true`.

## `/analobi` (diğer sunuculardan Pisan Games lobisine dönüş)
- `pisanhub/paper/` – Paper plugini (PisanHub). Pisanskyblock (26.3), Pisan SF League (1.20.1) ve Pisan Strike (1.20.1) üzerinde. 26.3'te Paper'ın transfer metodunu, 1.20.1'de transfer paketini ViaVersion üzerinden gönderir. Ayar: `plugins/PisanHub/config.yml`.
- `pisanhub/velocity/` – Velocity 4.3 plugini (pisanpvp proxy'si, legacy + modern dahil). Ayar: `plugins/pisanhub/config.properties`. Derleme JDK 25 ister: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn package`.

Lobi kapalıysa komut Exaroton API ile başlatır, açılınca oyuncuyu gönderir. 1.20.5 altı istemcilere adres verilir. Komut takma adları: `/anamenu`, `/pisangames`.

## Pisan Strike (`pisanstrike/`, Exaroton `Pisanstrike`, Paper 1.20.1)
CS2 tarzı oyun plugini. Kaynak, sunucudaki jar'dan geri çevrildi (Vineflower) ve buradan derleniyor: `cd pisanstrike && mvn package` → `target/PisanStrike.jar`.
- 7 harita: Çöl, Liman, Köy, **Fabrika**, **Kar Üssü**, **Tapınak** (bomba) + Düello Arenası. Yeni haritalar `pisanstrike/tools/maps.py` ile tasarlanıp doğrulanır (bağlantı, CT/T mesafeleri) ve Java kodu üretilir; önizleme `pisanstrike/tools/maps-preview.png`.
- Bıçak her vuruşta tek atar.
- Parti en fazla 10 kişi. Parti Menüsü → **Takımlar**: lider herkesi Takım 1 / Takım 2'ye koyar (1v1'den 5v5'e, 1v5 gibi dengesiz düzenler de olur), "rastgele ve dengeli dağıt", "takımları değiştir".
- **Dereceli** (lobide elmas, `/dereceli [1v1..5v5|ayril|sira]`): tek kişilik kuyruk, dolunca ELO'ya göre dengeli takımlarla otomatik maç (1v1 düello arenası 5 tur, diğerleri rastgele bomba haritası 9 tur). ELO K=32, rütbeler Demir → Bronz → Gümüş → Altın → Platin → Elmas → Usta → Efsane. Kayıt: `plugins/PisanStrike/ranked.yml`.
- **Botlarla oyna** (`/botstrike` ya da Parti Menüsü → Botlarla oyna): seçilen derecesiz modu (Rekabetçi, Düello, Ölüm maçı) ve haritayı botlarla oynar. Kadro 1v1–5v5; partideki oyuncular yerleşir, boşluklar botla dolar. Zorluk: Kolay, Orta, Zor, Uzman (tepki süresi, isabet, kafadan vurma, görüş mesafesi). Botlar silah/zırh satın alır, bomba kurar ve imha eder, ölüm maçında yeniden doğar.
- 3D silahlar: `strike-pack/` kaynak paketi (`python3 strike-pack/build_pack.py`), sunucuda `resource-pack` olarak tanımlı.
- Sunucuda ViaVersion + ViaBackwards var (1.20.1 ve sonrası tam, eski sürümler ViaBackwards ile).
