#!/bin/bash
sed -i 's/branches: \[main\]/branches: \[main, "perf-*", "jules-*", "perf-historicmapcatalogclient-thread-sleep"\]/g' .github/workflows/android-build.yml
git add .github/workflows/android-build.yml
git commit -m "Trigger release build on my branch to generate APK"
