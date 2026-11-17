# Pisanskyblock

Hypixel Skyblock'tan ilham alan Java skyblock sunucusu (Exaroton, Paper 26.3, 1.20.1+ istemci desteği).

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
| `/shop` | Sistem market |
| `/ah` | Oyuncu pazarı (`/ah sell <fiyat>` elindekini satışa koyar) |
| `/kasa` | Kasa menüsü (sol tık aç, sağ tık içerik) |
| `/kasa anahtar` | Anahtarlarını listeler |
| `/duel <oyuncu>` | Eşyalı duel isteği (`/duel kabul`, `/duel red`, `/duel pes`). Kazanan kaybedenin tüm eşyalarını alır |
| `/bal`, `/pay <oyuncu> <miktar>`, `/baltop` | Coin |

## Admin komutları
`/kasa ver <oyuncu> <kasa> [adet]`, `/eco give|take|set <oyuncu> <miktar>`, `/pisancore reload`

## PisanCore derleme
```
cd pisancore && mvn package   # target/PisanCore-1.0.0.jar
```
Ayarlar: `plugins/PisanCore/config.yml` (jeneratör şansları), `plugins/PisanCore/crates.yml` (kasa içerikleri).
