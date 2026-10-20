#!/usr/bin/env bash
# Launches RuneLite with the OSRS MCP plugin built in (macOS / Linux).
# Extra arguments are passed to the RuneLite client.
# Developer mode (with -ea) is required for RuneLite's Jagex account login in self-built clients.
# Set OSRS_MCP_DRY_RUN=1 to print the final command instead of starting the client.
cd "$(dirname "$0")/.." || exit 1

JAR="build/libs/osrs-mcp-runelite.jar"

if ! command -v java >/dev/null 2>&1; then
  echo "Java was not found. Install Java 11 or newer from https://adoptium.net/temurin/releases/"
  exit 1
fi

ver=$(java -version 2>&1 | head -n1 | sed -E 's/.*version "([^"]+)".*/\1/')
major=${ver%%.*}
if [ "$major" = "1" ]; then major=$(echo "$ver" | cut -d. -f2); fi
major=${major%%[!0-9]*}
if [ -z "$major" ] || [ "$major" -lt 11 ]; then
  echo "Java 11 or newer is required (found \"$ver\"). Install from https://adoptium.net/temurin/releases/"
  exit 1
fi

if [ "$OSRS_MCP_DRY_RUN" != "1" ] && [ -d .git ] && command -v git >/dev/null 2>&1; then
  if git pull --ff-only >/dev/null 2>&1; then
    echo "Plugin source is up to date."
  else
    echo "Note: could not update the plugin source (git pull skipped)."
  fi
fi

echo "Building (this also picks up the latest RuneLite)..."
if ! ./gradlew -q shadowJar; then
  echo
  echo "Build failed. Check your internet connection and that Java 11+ is installed,"
  echo "then try again. If it keeps failing, re-download the project from GitHub."
  exit 1
fi

if [ ! -f "$JAR" ]; then
  echo "Expected $JAR was not produced."
  exit 1
fi

if [ "$OSRS_MCP_DRY_RUN" = "1" ]; then
  echo "DRY RUN: nohup java -ea -jar $JAR --developer-mode $*"
  exit 0
fi

nohup java -ea -jar "$JAR" --developer-mode "$@" >/dev/null 2>&1 &
