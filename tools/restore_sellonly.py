import yaml,glob,re,os,sys
DEF='/tmp/claude-0/-home-user-Pisanskyblock/5699871d-4f3a-5d09-a84d-1eda1e47e916/scratchpad/esgui/261-shops'
BAN=re.compile(r'NETHERITE|SPEAR|TRIDENT|^MACE$|HEAVY_CORE|ELYTRA|TOTEM_OF_UNDYING|^BEACON$|ENCHANTED_GOLDEN_APPLE|^VAULT$|SHULKER|DRAGON_EGG|DRAGON_HEAD|NETHER_STAR|END_CRYSTAL|ANCIENT_DEBRIS|WITHER_SKELETON_SKULL|^(WITHER|ENDER_DRAGON|WARDEN|ELDER_GUARDIAN|VILLAGER|ZOMBIE_VILLAGER)_SPAWN_EGG$')
apply='--apply' in sys.argv
added=[]
for df in sorted(glob.glob(DEF+'/**/*.yml',recursive=True)):
    rel=os.path.relpath(df,DEF); cur='shops/'+rel
    if not os.path.exists(cur): continue
    d=yaml.safe_load(open(df)); c=yaml.safe_load(open(cur))
    ch=False
    for pn,pg in (d.get('pages') or {}).items():
        dit=pg.get('items') or {}
        cp=c.setdefault('pages',{}).setdefault(pn,{}); cit=cp.get('items') or {}
        new={}; keys=list(dit)
        # mevcut sirayi koru, eksik yasaklilari orijinal yerlerine ekle
        order=[]
        for k in keys:
            v=dit[k]
            if k in cit: order.append(k)
            elif 'section' not in v and (BAN.search(str(v.get('material','')).upper()) or 'MENDING' in ' '.join(map(str,v.get('enchantments') or []))):
                e=dict(v); e['buy']=-1
                if not e.get('sell') or e['sell']<=0: continue
                cit[k]=e; order.append(k); added.append((rel,k,e['sell'],e.get('stack-size',1))); ch=True
        for k in cit:
            if k not in order: order.append(k)
        cp['items']={k:cit[k] for k in order}
    if ch and apply: open(cur,'w').write(yaml.safe_dump(c,sort_keys=False,allow_unicode=True))
for a in added: print(a)
print(len(added),'eklenecek' if not apply else 'eklendi')
