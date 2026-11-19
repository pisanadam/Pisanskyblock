#!/bin/bash
# deploy.sh [remote_path local_path]...  -> stop, upload, start, show log
E="$(dirname "$0")/exa.sh"
st(){ $E GET / 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(d['status'],d['players']['count'])" 2>/dev/null || echo x; }
call(){ for t in $(seq 1 8); do r=$($E "$@" 2>/dev/null); echo "$r" | grep -q '"success":true' && { echo "ok $2"; return 0; }; sleep 8; done; echo "FAIL $2"; }
b=$(st); echo "before: $b"
if [ "${b##* }" != "0" ] && [ "${b##* }" != "x" ]; then
  $E POST /command/ -H "Content-Type: application/json" -d '{"command":"say Sunucu guncelleme icin 30 saniye icinde yeniden baslatilacak!"}' >/dev/null 2>&1
  sleep 30
fi
call GET /stop/
for i in $(seq 1 30); do s=$(st); [ "${s%% *}" = 0 ] && break; sleep 5; done; echo "status $s"
if [ "${s%% *}" != 0 ]; then echo "ABORT: sunucu kapanmadi, dosya yuklenmedi"; exit 1; fi
while [ $# -ge 2 ]; do call PUT "/files/data/$1" -H "Content-Type: application/octet-stream" --data-binary @"$2"; shift 2; done
call GET /start/
for i in $(seq 1 45); do s=$(st); [ "${s%% *}" = 1 ] && break; [ "${s%% *}" = 7 ] && break; sleep 8; done; echo "status $s"
sleep 5
for t in 1 2 3 4 5 6; do l=$($E GET /logs/ 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['content'])" 2>/dev/null) && { echo "$l" | grep -iE "PisanCore|SEVERE|ERROR|Exception|Done \(" | head -20; break; }; sleep 8; done
