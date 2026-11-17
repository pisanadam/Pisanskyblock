import yaml,glob,re,sys
BAN=re.compile(r'NETHERITE|SPEAR|TRIDENT|^MACE$|HEAVY_CORE|ELYTRA|TOTEM_OF_UNDYING|^BEACON$|ENCHANTED_GOLDEN_APPLE|SPAWNER|^VAULT$|SHULKER|DRAGON_EGG|DRAGON_HEAD|NETHER_STAR|END_CRYSTAL|ANCIENT_DEBRIS|WITHER_SKELETON_SKULL|^(WITHER|ENDER_DRAGON|WARDEN|ELDER_GUARDIAN|VILLAGER|ZOMBIE_VILLAGER)_SPAWN_EGG$')
removed=[]
for f in sorted(glob.glob('shops/**/*.yml',recursive=True)):
    d=yaml.safe_load(open(f)) or {}
    ch=False
    for pn,page in (d.get('pages') or {}).items():
        items=page.get('items') or {}
        for k in list(items):
            it=items[k] or {}
            if 'section' in it: continue
            mat=str(it.get('material','')).upper()
            ench=' '.join(map(str,it.get('enchantments') or []))
            if BAN.search(mat) or 'MENDING' in ench:
                removed.append(f'{f}:{k}'); del items[k]; ch=True
    if ch: open(f,'w').write(yaml.safe_dump(d,sort_keys=False,allow_unicode=True))
print(len(removed)); print('\n'.join(removed))
