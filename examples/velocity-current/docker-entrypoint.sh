#!/bin/bash
set -e

if [ ! -f /usr/bin/docker ] || ! /usr/bin/docker version > /dev/null 2>&1; then
  apt-get update && apt-get install -y docker.io && apt-get clean
fi

SOCKET_GID=$(stat -c '%g' /var/run/docker.sock)
if ! getent group "$SOCKET_GID" > /dev/null; then
  groupadd -g "$SOCKET_GID" docker_sock
fi
GROUP_NAME=$(getent group "$SOCKET_GID" | cut -d: -f1)
usermod -aG "$GROUP_NAME" bungeecord

exec /usr/bin/run-bungeecord.sh