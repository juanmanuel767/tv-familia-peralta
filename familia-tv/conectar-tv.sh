#!/usr/bin/env bash
# Conecta adb con la TV sin código: busca en el wifi el puerto actual de la Depuración inalámbrica.
set -uo pipefail
TV_IP="${1:-192.168.20.17}"
for intento in 1 2 3; do
  puerto=$(timeout 8 avahi-browse -rpt _adb-tls-connect._tcp 2>/dev/null \
           | awk -F';' -v ip="$TV_IP" '$1=="=" && $8==ip {print $9; exit}')
  if [ -n "$puerto" ]; then
    adb connect "$TV_IP:$puerto" && exit 0
  fi
  sleep 2
done
echo "No encontré la TV. Revisa que esté encendida y con la Depuración inalámbrica activa." >&2
exit 1
