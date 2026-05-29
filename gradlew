#!/bin/sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROPERTIES_FILE="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "$PROPERTIES_FILE" ]; then
  echo "Missing $PROPERTIES_FILE" >&2
  exit 1
fi

distribution_url=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES_FILE" | sed 's/\\:/:/g')
gradle_version=$(printf '%s\n' "$distribution_url" | sed -n 's/.*gradle-\([^-]*\)-bin.zip.*/\1/p')

if [ -z "$distribution_url" ] || [ -z "$gradle_version" ]; then
  echo "Could not parse Gradle distribution URL from $PROPERTIES_FILE" >&2
  exit 1
fi

cache_dir="${GRADLE_USER_HOME:-$APP_HOME/.gradle}/wrapper/dists/gradle-$gradle_version-bin"
gradle_home="$cache_dir/gradle-$gradle_version"
zip_file="$cache_dir/gradle-$gradle_version-bin.zip"

if [ ! -x "$gradle_home/bin/gradle" ]; then
  mkdir -p "$cache_dir"
  if [ ! -f "$zip_file" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -L --fail "$distribution_url" -o "$zip_file"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$zip_file" "$distribution_url"
    else
      echo "curl or wget is required to download Gradle." >&2
      exit 1
    fi
  fi
  unzip -q "$zip_file" -d "$cache_dir"
fi

exec "$gradle_home/bin/gradle" "$@"
