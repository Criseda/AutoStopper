#!/bin/bash
set -euo pipefail

if [ ! -f /usr/bin/docker ] || ! /usr/bin/docker version > /dev/null 2>&1; then
  apt-get update
  apt-get install -y --no-install-recommends docker.io
  apt-get clean
  rm -rf /var/lib/apt/lists/*
fi

install -m 0755 /e2e/docker-cli-wrapper.sh /usr/local/bin/docker

SOCKET_GID=$(stat -c '%g' /var/run/docker.sock)
if ! getent group "$SOCKET_GID" > /dev/null; then
  groupadd -g "$SOCKET_GID" docker_sock
fi
GROUP_NAME=$(getent group "$SOCKET_GID" | cut -d: -f1)
usermod -aG "$GROUP_NAME" bungeecord

exec /usr/bin/run-bungeecord.sh
