#!/usr/bin/env bash
# Run AFTER 'mvn clean package' to confirm the fat JAR is correct.
set -uo pipefail
cd "$(dirname "$0")"

# ── Find the JAR (whatever name Maven gave it) ────────────────────────
echo "=== 1. JAR file ==="
JAR=$(find target -maxdepth 1 -name "*.jar" ! -name "*-thin*" ! -name "original-*" 2>/dev/null | head -1)

if [ -z "$JAR" ]; then
    echo "MISSING: no JAR found in target/"
    echo ""
    echo "Contents of target/ (if it exists):"
    ls -lh target/ 2>/dev/null || echo "  target/ does not exist"
    echo ""
    echo "Run:  mvn clean package"
    echo "Then paste the full Maven output here if it still fails."
    exit 1
fi
echo "Found: $JAR"
ls -lh "$JAR"

# ── MANIFEST ─────────────────────────────────────────────────────────
echo ""
echo "=== 2. MANIFEST.MF ==="
unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null || { echo "ERROR: cannot read manifest"; exit 1; }

# ── Main-Class header ────────────────────────────────────────────────
echo ""
echo "=== 3. Main-Class header ==="
MAIN=$(unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | grep "^Main-Class" || true)
if [ -n "$MAIN" ]; then
    echo "OK  $MAIN"
else
    echo "MISSING – Main-Class not in manifest. Re-run: mvn clean package"
    exit 1
fi

# ── Our classes ───────────────────────────────────────────────────────
echo ""
echo "=== 4. Application classes ==="
ALL_OK=1
for cls in \
    com/soundboard/Main.class \
    com/soundboard/MainWindow.class \
    com/soundboard/AudioEngine.class \
    com/soundboard/GridPanel.class \
    com/soundboard/SoundButton.class \
    com/soundboard/ButtonData.class \
    com/soundboard/ButtonPropertiesDialog.class \
    com/soundboard/WaveformPanel.class \
    com/soundboard/HotkeyUtil.class \
    com/soundboard/LayoutData.class
do
    if unzip -l "$JAR" "$cls" &>/dev/null; then
        echo "OK  $cls"
    else
        echo "MISSING  $cls"
        ALL_OK=0
    fi
done
[ "$ALL_OK" -eq 1 ] || { echo "Some classes missing – check compile errors above."; exit 1; }

# ── Dependencies ──────────────────────────────────────────────────────
echo ""
echo "=== 5. Bundled dependencies ==="
JLAYER=$(unzip -l "$JAR" 2>/dev/null | grep -c "javazoom" || true)
GSON=$(unzip -l "$JAR" 2>/dev/null | grep -c "com/google/gson" || true)
echo "JLayer classes : $JLAYER  (need > 0)"
echo "Gson classes   : $GSON  (need > 0)"
if [ "$JLAYER" -eq 0 ] || [ "$GSON" -eq 0 ]; then
    echo "ERROR: dependencies not bundled – fat JAR incomplete."
    exit 1
fi

echo ""
echo "=== All checks passed ==="
echo "Run with:  java -jar $JAR"
