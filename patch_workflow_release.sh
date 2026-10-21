#!/bin/bash
sed -i "s/if: github.event_name == 'push' && github.ref == 'refs\/heads\/main'/if: github.event_name == 'push'/g" .github/workflows/android-build.yml
git add .github/workflows/android-build.yml
git commit --amend --no-edit
