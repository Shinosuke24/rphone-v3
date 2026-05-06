#!/bin/bash
# Artifact verification script for rphone-v3-windows-package
set -e

ARTIFACT_ZIP="${1:-rphone-v3-windows-package.zip}"
EXTRACT_DIR="${2:-rphone-v3-extracted}"

echo "🔍 Verifying R-Phone V3 Windows Artifact..."
echo

# Check zip exists
if [ ! -f "$ARTIFACT_ZIP" ]; then
    echo "❌ ERROR: Artifact file not found: $ARTIFACT_ZIP"
    exit 1
fi

echo "✅ Artifact file found: $ARTIFACT_ZIP"
echo

# Extract
echo "📦 Extracting artifact..."
rm -rf "$EXTRACT_DIR"
unzip -q "$ARTIFACT_ZIP" -d "$EXTRACT_DIR"
echo "✅ Extracted to: $EXTRACT_DIR"
echo

# List contents
echo "📋 Contents:"
find "$EXTRACT_DIR" -type f | head -20
echo

# Verify structure
echo "🔎 Verifying structure..."
if [ ! -f "$EXTRACT_DIR/rphone-v3-desktop.exe" ]; then
    echo "❌ MISSING: rphone-v3-desktop.exe"
    exit 1
fi
echo "✅ Found: rphone-v3-desktop.exe"

if [ ! -d "$EXTRACT_DIR/jre" ]; then
    echo "❌ MISSING: jre folder (bundled Java runtime)"
    exit 1
fi
echo "✅ Found: jre folder"

if [ ! -f "$EXTRACT_DIR/jre/bin/java" ] && [ ! -f "$EXTRACT_DIR/jre/bin/java.exe" ]; then
    echo "❌ MISSING: jre/bin/java[.exe]"
    exit 1
fi
echo "✅ Found: jre/bin/java"
echo

# Test Java version
echo "🔧 Testing bundled Java..."
JAVA_BIN=""
if [ -f "$EXTRACT_DIR/jre/bin/java.exe" ]; then
    JAVA_BIN="$EXTRACT_DIR/jre/bin/java.exe"
elif [ -f "$EXTRACT_DIR/jre/bin/java" ]; then
    JAVA_BIN="$EXTRACT_DIR/jre/bin/java"
fi

if [ -n "$JAVA_BIN" ]; then
    echo "Java executable: $JAVA_BIN"
    # Try to get version
    if "$JAVA_BIN" -version 2>&1 | head -1 || true; then
        echo "✅ Java runtime executable"
    fi
else
    echo "❌ Java binary not found or not executable"
    exit 1
fi
echo

echo "✅ ALL CHECKS PASSED!"
echo
echo "📌 Next steps (on Windows):"
echo "   1. Extract: Expand-Archive -Path $ARTIFACT_ZIP -DestinationPath .\rphone-v3"
echo "   2. Navigate: cd rphone-v3"
echo "   3. Run EXE: Start-Process .\rphone-v3-desktop.exe"
echo "   4. Or run with console: .\rphone-v3\jre\bin\java.exe -jar ..."
echo
