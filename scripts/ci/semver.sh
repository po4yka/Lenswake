#!/usr/bin/env bash

# SemVer 2.0.0, including prerelease and build metadata. Numeric identifiers
# must not contain leading zeroes.
readonly LENSWAKE_SEMVER_PATTERN='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*)|([0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))(\.((0|[1-9][0-9]*)|([0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)))*)?(\+([0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*))?$'

is_lenswake_semver() {
  [[ "$1" =~ $LENSWAKE_SEMVER_PATTERN ]]
}
