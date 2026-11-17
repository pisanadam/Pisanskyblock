#!/bin/bash
# usage: exa.sh METHOD PATH [curl args...]
EXA="${EXAROTON_TOKEN:?EXAROTON_TOKEN ayarla}"
M=$1; P=$2; shift 2
curl -sS -m 300 -X $M -H "Authorization: Bearer $EXA" "https://api.exaroton.com/v1/servers/${EXAROTON_SERVER:-xSQJtk6ig0N4wEgt}$P" "$@"
