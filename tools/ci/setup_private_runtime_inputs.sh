#!/usr/bin/env bash
set -euo pipefail

cat <<'EOF'
BanjoRecomp Android full runtime build setup

1. Create a PRIVATE GitHub repository named:
   8cee/BanjoRecomp-private-inputs

2. Put this file at the repository root:
   baserom.us.v10.z64

3. Create a GitHub Personal Access Token that can read that private repository.

4. In 8cee/BanjoRecomp-Android:
   Settings -> Secrets and variables -> Actions -> New repository secret

   Name:
     PRIVATE_REPO_TOKEN

   Value:
     <your token>

5. Push/update the runtime-ci branch or run the Android APK workflow in runtime mode.

CI verifies the normal US v1.0 ROM and generates the decompressed runtime image automatically. The ROM input is used only to generate runtime source files.
The workflow removes banjo.us.v10.decompressed.z64 before APK packaging.
EOF
