#!/usr/bin/env bash
# Build transitloop/transitime for the MicroK8s nodes (linux/amd64).
# Set PUSH=1 to send the tag to Docker Hub after docker login.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${IMAGE:-transitloop/transitime}"
TAG="${TAG:-latest}"
PLATFORM="${PLATFORM:-linux/amd64}"

args=(buildx build --platform "${PLATFORM}" -f "${ROOT}/deploy/Dockerfile" -t "${IMAGE}:${TAG}")
if [[ "${PUSH:-}" == "1" ]]; then
  args+=(--push)
else
  args+=(--load)
fi

docker "${args[@]}" "${ROOT}"

if [[ "${PUSH:-}" == "1" ]]; then
  printf 'Pushed %s:%s\n' "${IMAGE}" "${TAG}"
else
  printf 'Built %s:%s locally. Run with PUSH=1 to send it to Docker Hub.\n' "${IMAGE}" "${TAG}"
fi
