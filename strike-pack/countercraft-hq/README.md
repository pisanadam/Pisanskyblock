# Counter Craft HQ (optimize)

Kaynak: kullanıcının HQ 384 kaynak paketi (Counter Craft). Orijinal modeller silah başına 1.600–19.400 küp ve
1024 px dokuydu; Minecraft'ta kare hızını ~10 kat düşürüyor, blok atlasını 16384 px'e büyütüyordu.

`strike-pack/tools/hq_reduce.py` ile indirgendi:
- her silah, oyundaki gerçek boyutuna göre seçilen bir ızgarada yeniden küplere ayrıldı (`grids.json`: tabancalar 28–40, SMG 48–56, tüfekler 64),
- renkler silah başına 24 renklik palete indirildi (16×16 doku),
- aynı renkteki komşu hücreler kutulara birleştirildi, birbirine değen görünmez yüzler çıkarıldı.

Sonuç: toplam ~190.000 küp → ~14.400 kutu, ~30.600 yüz; dokular 26 MB → 120 KB.
Dual Berettas için HQ yerine standart Counter Craft modeli kullanılır (`build_pack.py` → `HQ_SKIP`).

Yeniden üretmek için (orijinal HQ dosyaları gerekir):
`python3 strike-pack/tools/hq_reduce.py <hq_kaynak> strike-pack/countercraft-hq --grid <ızgara> --colors 24 --names <adlar>`
