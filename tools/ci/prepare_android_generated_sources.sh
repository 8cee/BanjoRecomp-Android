#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-runtime}"
PRIVATE_INPUTS_DIR="${BANJO_ANDROID_PRIVATE_INPUTS_DIR:-extra}"
N64RECOMP_SOURCE_DIR="${BANJO_ANDROID_N64RECOMP_SOURCE_DIR:-lib/N64ModernRuntime/N64Recomp}"
N64RECOMP_BUILD_DIR="${BANJO_ANDROID_N64RECOMP_BUILD_DIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/banjo-n64recomp-build}"
FILE_TO_C="${BANJO_ANDROID_FILE_TO_C:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/banjo-file-to-c}"

have_runtime_sources() {
  (compgen -G 'RecompiledFuncs/*.c' >/dev/null || compgen -G 'RecompiledFuncs/*.cpp' >/dev/null) && \
  [[ -f rsp/n_aspMain.cpp ]] && \
  [[ -f RecompiledPatches/patches.c ]]
}

rom_inputs_from_toml() {
  python3 - <<'PY'
from pathlib import Path
import re

seen = set()
for path in Path('.').glob('*.toml'):
    for line in path.read_text(encoding='utf-8').splitlines():
        match = re.match(r'\s*rom_file_path\s*=\s*"([^"]+)"', line)
        if match:
            value = match.group(1)
            if value not in seen:
                seen.add(value)
                print(value)
PY
}

build_recomp_tools() {
  if [[ -x ./N64Recomp && -x ./RSPRecomp ]]; then
    echo "N64Recomp and RSPRecomp are already present."
    return
  fi

  if [[ ! -d "$N64RECOMP_SOURCE_DIR" ]]; then
    echo "N64Recomp source directory is missing: $N64RECOMP_SOURCE_DIR" >&2
    exit 2
  fi

  echo "Building N64Recomp/RSPRecomp from $N64RECOMP_SOURCE_DIR."
  cmake \
    -S "$N64RECOMP_SOURCE_DIR" \
    -B "$N64RECOMP_BUILD_DIR" \
    -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM=ninja
  cmake --build "$N64RECOMP_BUILD_DIR" --config Release --target N64Recomp RSPRecomp -j "$(nproc)"
  cp "$N64RECOMP_BUILD_DIR/N64Recomp" ./N64Recomp
  cp "$N64RECOMP_BUILD_DIR/RSPRecomp" ./RSPRecomp
  chmod +x ./N64Recomp ./RSPRecomp
}

build_file_to_c() {
  if [[ -x "$FILE_TO_C" ]]; then
    return
  fi

  local source_path="lib/rt64/src/tools/file_to_c/file_to_c.cpp"
  if [[ ! -f "$source_path" ]]; then
    echo "file_to_c source is missing: $source_path" >&2
    exit 2
  fi

  echo "Building host file_to_c helper."
  c++ -std=c++17 -O2 "$source_path" -o "$FILE_TO_C"

  if [[ -n "${GITHUB_PATH:-}" ]]; then
    local host_tools_dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/banjo-host-tools"
    mkdir -p "$host_tools_dir"
    cp "$FILE_TO_C" "$host_tools_dir/file_to_c"
    chmod +x "$host_tools_dir/file_to_c"
    echo "$host_tools_dir" >> "$GITHUB_PATH"
  fi
}

copy_private_inputs() {
  if [[ ! -d "$PRIVATE_INPUTS_DIR" ]]; then
    cat >&2 <<MSG
Runtime APK build needs generated BanjoRecomp sources, but they are missing.

This workflow follows the upstream extra/ pattern. Provide a private input
repository checkout at:
  $PRIVATE_INPUTS_DIR

The private input repository should contain files expected by the public TOML
configuration at its root.

Probe builds can run without this by setting build_mode=probe.
MSG
    exit 2
  fi

  shopt -s nullglob dotglob
  local inputs=("$PRIVATE_INPUTS_DIR"/*)
  shopt -u nullglob dotglob
  if [[ ${#inputs[@]} -eq 0 ]]; then
    echo "Private input directory is empty: $PRIVATE_INPUTS_DIR" >&2
    exit 2
  fi

  echo "Copying private inputs from $PRIVATE_INPUTS_DIR into the repository root."
  cp -a "$PRIVATE_INPUTS_DIR"/* .
}

python3 tools/ci/patch_android_recompfrontend.py

if [[ "$MODE" == "probe" ]]; then
  echo "Probe build selected; generated game sources are not required."
  exit 0
fi

if have_runtime_sources; then
  echo "Generated runtime sources are already present."
  exit 0
fi

copy_private_inputs

prepare_decompressed_rom() {
  local output="banjo.us.v10.decompressed.z64"
  [[ -f "$output" ]] && return

  local normal_rom=""
  for candidate in     "baserom.us.v10.z64"     "banjo.us.v10.z64"     "Banjo-Kazooie (USA).z64"     "Banjo-Kazooie.z64"
  do
    if [[ -f "$candidate" ]]; then
      normal_rom="$candidate"
      break
    fi
  done

  if [[ -z "$normal_rom" ]]; then
    mapfile -t roms < <(find "$PRIVATE_INPUTS_DIR" . -maxdepth 1 -type f -iname '*.z64' -print 2>/dev/null | sort -u)
    if [[ ${#roms[@]} -eq 1 ]]; then
      normal_rom="${roms[0]}"
    fi
  fi

  if [[ -z "$normal_rom" || ! -f "$normal_rom" ]]; then
    echo "Runtime build needs either $output or a normal Banjo-Kazooie US v1.0 .z64 ROM." >&2
    exit 2
  fi

  local expected_sha1="1fe1632098865f639e22c11b9a81ee8f29c75d7a"
  local actual_sha1
  actual_sha1="$(sha1sum "$normal_rom" | awk '{print $1}')"
  if [[ "$actual_sha1" != "$expected_sha1" ]]; then
    echo "Unsupported Banjo-Kazooie ROM for runtime generation." >&2
    echo "Expected US v1.0 SHA1: $expected_sha1" >&2
    echo "Actual SHA1:          $actual_sha1" >&2
    exit 2
  fi

  local decomp_dir="lib/bk-decomp"
  if [[ ! -d "$decomp_dir" ]]; then
    echo "Banjo decomp submodule is missing: $decomp_dir" >&2
    exit 2
  fi

  echo "Preparing decompressed Banjo-Kazooie ROM from the normal US v1.0 ROM."
  cp "$normal_rom" "$decomp_dir/baserom.us.v10.z64"

  local venv="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/banjo-decomp-venv"
  python3 -m venv "$venv"
  "$venv/bin/python" -m pip install --upgrade pip
  "$venv/bin/python" -m pip install -r "$decomp_dir/requirements.txt"

  (
    cd "$decomp_dir"
    PATH="$venv/bin:$PATH" make VERSION=us.v10
  )

  local generated=""
  for candidate in     "$decomp_dir/build/us.v10/decompressed.us.v10.z64"     "$decomp_dir/build/us.v10/banjo.us.v10.decompressed.z64"     "$decomp_dir/decompressed.us.v10.z64"
  do
    if [[ -f "$candidate" ]]; then
      generated="$candidate"
      break
    fi
  done

  if [[ -z "$generated" ]]; then
    generated="$(find "$decomp_dir/build" -type f -iname '*decompressed*.z64' -print -quit 2>/dev/null || true)"
  fi

  if [[ -z "$generated" || ! -f "$generated" ]]; then
    echo "Banjo decomp completed but no decompressed US v1.0 ROM was found." >&2
    find "$decomp_dir/build" -maxdepth 3 -type f -iname '*.z64' -print 2>/dev/null || true
    exit 2
  fi

  cp "$generated" "$output"
  rm -f "$decomp_dir/baserom.us.v10.z64"
  echo "Generated $output."
}

prepare_decompressed_rom
build_recomp_tools
build_file_to_c

./N64Recomp banjo.us.rev0.toml
./RSPRecomp n_aspMain.us.rev0.toml
CC="${PATCHES_C_COMPILER:-clang}" LD="${PATCHES_LD:-ld.lld}" make -C patches
./N64Recomp patches.toml
"$FILE_TO_C" patches/patches.bin bk_patches_bin RecompiledPatches/patches_bin.c RecompiledPatches/patches_bin.h

if ! have_runtime_sources; then
  echo "Runtime source generation completed, but required generated files are still missing." >&2
  exit 2
fi

# Private inputs are needed only while generating sources. Remove both the normal
# baserom and decompressed ROM before Gradle packaging so game assets cannot be
# bundled into the APK.
while IFS= read -r rom_input; do
  [[ -n "$rom_input" ]] && rm -f -- "$rom_input"
done < <(rom_inputs_from_toml)
rm -f -- baserom.us.v10.z64 banjo.us.v10.z64 "Banjo-Kazooie (USA).z64" "Banjo-Kazooie.z64"
rm -f -- lib/bk-decomp/baserom.us.v10.z64

echo "Generated runtime sources are ready."
