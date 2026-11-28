# Pisan Skyblock/SMP

Tek sunucuda iki mod (Exaroton `Pisanskyblock`, Paper 26.3, 1.20.1+ istemci desteği):
- **Skyblock** – Hypixel Skyblock'tan ilham alan ada modu.
- **SMP** – 25.000 x 25.000 sınırlı hayatta kalma dünyası (`pisan_smp`, kendi nether'ı (sınır 3.125) ve end'i ile).

Girişte **Mod Seçimi** lobisine gelinir ve menü açılır: Skyblock → Sky Lobi, SMP → SMP Lobi
(kapılardan yürüyerek de geçilir). Coin, market, pazar, kasalar/anahtarlar, skill, duel, takas ve arkadaşlar
iki modda ortaktır; envanter de ortaktır. Minyonlar sadece adada çalışır.
SMP'den çıkınca son konum saklanır, `/smp` ile kaldığın yere dönersin.

## Klasörler
- `pisancore/` – Sunucuya özel plugin (Maven): coin ekonomisi (Vault), taş jeneratörü ödülleri, 10 kademe kasa sistemi.
- `server-config/` – Sunucuya yüklenen düzenlenmiş config dosyaları (market, pazar, BentoBox).
- `design/` – Tasarım dokümanı.
- `tools/` – Yardımcı scriptler (`exa.sh`: Exaroton API, `EXAROTON_TOKEN` ortam değişkeni ister).

## Sunucudaki pluginler
ViaVersion + ViaBackwards, BentoBox + BSkyBlock, LuckPerms, VaultUnlocked, PlaceholderAPI,
EconomyShopGUI (market), AuctionHouse (oyuncu pazarı), GrimAC (anti-hile), PisanCore.

Ada koruma alanı: 500x500 (protection-range 250).

## Oyuncu komutları
| Komut | İş |
|---|---|
| `/is` | Ada menüsü (ilk girişte ada otomatik açılır) |
| `/shop` (`/magaza`) | Sistem market |
| `/sat` (`/sell`) | Satış penceresi (büyülü eşya/kitaplar dahil). `/sat el`: elindekini sat, `/sat fiyat`: değerine bak |
| `/sellall <inventory\|hand\|eşya>` (`/hepsinisat`) | Toplu satış |
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
| `/menu` (`/hub`) | Mod seçimi (Skyblock / SMP) |
| `/smp` | SMP dünyasına gir (kaldığın yer, ilk seferde SMP spawn) |
| `/lobi` (`/spawn`) | Bulunduğun modun lobisine ışınlan (`/skylobi`, `/smplobi`). Lobideki kasalara sol tık: anahtarla aç, sağ tık: içerik ve şanslar |
| `/bal`, `/pay <oyuncu> <miktar>`, `/baltop` | Coin |

## Admin komutları
`/kasa ver <oyuncu> <kasa> [adet]`, `/minyon ver <oyuncu> <tür> [seviye] [adet]`, `/event satis|kazma|anahtar <değer> <dk>`, `/event gorev <kaz|oldur|hasat> <adet> <dk>` (ödül: eldeki eşya), `/event huni <kat> <gün>` (spigot.yml, yeniden başlatınca), `/event durdur`, `/lobi spawnayarla` (bulunduğun lobi alanı), `/lobi yenidenkur [sky|smp|hub]`, `/lobi kasaayarla <kasa>`, `/smp spawnayarla|spawn|bilgi`, `/eco give|take|set <oyuncu> <miktar>`, `/pisancore reload`

## PisanCore derleme
```
cd pisancore && mvn package   # target/PisanCore-1.0.0.jar
```
Ayarlar: `plugins/PisanCore/config.yml` (jeneratör şansları), `plugins/PisanCore/crates.yml` (kasa içerikleri).
