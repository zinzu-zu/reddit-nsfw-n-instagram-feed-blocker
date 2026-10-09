#!/bin/bash
# Publishes the extension in ./extension as a new listed version on
# addons.mozilla.org. Firefox on every device then updates itself.
# Needs AMO_JWT_ISSUER and AMO_JWT_SECRET (from
# https://addons.mozilla.org/developers/addon/api/key/) and network access to
# addons.mozilla.org. Bump "version" in extension/manifest.json first.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
: "${AMO_JWT_ISSUER:?Set AMO_JWT_ISSUER}"
: "${AMO_JWT_SECRET:?Set AMO_JWT_SECRET}"

npx -y web-ext@8 lint --source-dir "$HERE/extension"
npx -y web-ext@8 sign \
  --source-dir "$HERE/extension" \
  --artifacts-dir "$HERE/signed" \
  --channel listed \
  --api-key "$AMO_JWT_ISSUER" \
  --api-secret "$AMO_JWT_SECRET"
