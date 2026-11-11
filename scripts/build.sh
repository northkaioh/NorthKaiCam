#!/usr/bin/env bash
# Monta a GCam SRK AIO Northkai a partir do APK original da SRK AIO.
#
# Uso: scripts/build.sh <srk_aio_original.apk> <keystore.jks> <alias> [saida.apk]
#
# Requisitos no PATH / variáveis:
#   - JDK 17+ (javac, jar, jarsigner)
#   - APKTOOL   -> caminho do apktool.jar        (padrão: tools/apktool.jar)
#   - ANDROID_JAR -> android.jar (API 28+)        (padrão: tools/android.jar)
#   - DEXTOOLS  -> pasta do dex-tools (dex2jar)   (padrão: tools/dex-tools)
#   - python3
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_APK="${1:?informe o APK original da SRK AIO}"
KEYSTORE="${2:?informe o keystore .jks}"
ALIAS="${3:?informe o alias da chave}"
OUT="${4:-$ROOT/GCam_SRK_Northkai.apk}"

APKTOOL="${APKTOOL:-$ROOT/tools/apktool.jar}"
ANDROID_JAR="${ANDROID_JAR:-$ROOT/tools/android.jar}"
DEXTOOLS="${DEXTOOLS:-$ROOT/tools/dex-tools}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Compilando RearPreview.java"
mkdir -p "$WORK/classes"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
      -d "$WORK/classes" "$ROOT/src/com/caio/rear/RearPreview.java"
(cd "$WORK/classes" && jar cf "$WORK/rear.jar" com)
"$DEXTOOLS/d2j-jar2dex.sh" -f -o "$WORK/rear.dex" "$WORK/rear.jar"

echo "==> Convertendo para smali"
mkdir -p "$WORK/dz" && cp "$WORK/rear.dex" "$WORK/dz/classes.dex"
(cd "$WORK/dz" && zip -q "$WORK/rear.zip" classes.dex)
java -jar "$APKTOOL" d -f -o "$WORK/rearsmali" "$WORK/rear.zip"

echo "==> Decompilando a SRK AIO (sem recursos)"
java -jar "$APKTOOL" d -r -f -o "$WORK/dec" "$SRC_APK"

echo "==> Aplicando patch"
(cd "$WORK/dec" && patch -p1 < "$ROOT/patches/srk-aio-northkai.patch")
mkdir -p "$WORK/dec/smali_classes3/com"
cp -r "$WORK/rearsmali/smali/com/caio" "$WORK/dec/smali_classes3/com/"

echo "==> Recompilando"
java -jar "$APKTOOL" b -o "$WORK/built.apk" "$WORK/dec"

echo "==> Trocando só os .dex no APK original (preserva libs e recursos)"
(cd "$WORK" && unzip -o -q built.apk classes.dex classes3.dex -d newdex)
python3 - "$SRC_APK" "$WORK/unsigned.apk" "$WORK/newdex" <<'EOF'
import sys, zipfile
src, out, nd = sys.argv[1:4]
z = zipfile.ZipFile(src); o = zipfile.ZipFile(out, 'w')
rep = {'classes.dex': f'{nd}/classes.dex', 'classes3.dex': f'{nd}/classes3.dex'}
for i in z.infolist():
    n = i.filename
    if n.startswith('META-INF/') and n.split('/')[-1].upper().endswith(('.SF', '.RSA', '.DSA', '.EC', 'MANIFEST.MF')):
        continue
    data = open(rep[n], 'rb').read() if n in rep else z.read(n)
    zi = zipfile.ZipInfo(n, date_time=i.date_time)
    zi.compress_type = i.compress_type; zi.external_attr = i.external_attr
    o.writestr(zi, data)
o.close()
EOF

echo "==> Assinando e alinhando"
jarsigner -keystore "$KEYSTORE" -sigalg SHA256withRSA -digestalg SHA-256 "$WORK/unsigned.apk" "$ALIAS"
python3 "$ROOT/scripts/zipalign.py" "$WORK/unsigned.apk" "$OUT"
jarsigner -verify "$OUT" >/dev/null && echo "==> Pronto: $OUT"
