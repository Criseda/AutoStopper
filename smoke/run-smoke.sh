#!/bin/sh
# POSIX counterpart of run-smoke.ps1: runs the packaged-JAR Velocity system tests.
# Usage: smoke/run-smoke.sh [legacy|stable-4.0|stable-4.1|stable|preview|all]
set -eu

profile="${1:-all}"
case "$profile" in
    legacy | stable-4.0 | stable-4.1 | stable | preview | all) ;;
    *)
        echo "Unknown profile: $profile" >&2
        echo "Usage: $0 [legacy|stable-4.0|stable-4.1|stable|preview|all]" >&2
        exit 2
        ;;
esac

repository=$(cd "$(dirname "$0")/.." && pwd)
cd "$repository"
exec ./mvnw verify -Psystem-tests "-Dvelocity.system.profiles=$profile"
