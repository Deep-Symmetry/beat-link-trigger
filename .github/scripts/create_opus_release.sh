#!/usr/bin/env bash

set -e  # Exit if any command fails.

# See if we are creating a preview release
if [ "$release_snapshot" = true ] ; then

    # Create (or move) the latest-opus-preview tag locally, then push it.
    echo "Creating tag for opus preview release"
    git config --global user.name 'James Elliott'
    git config --global user.email 'james@deepsymmetry.org'
    git tag latest-opus-preview -m "The latest opus preview release" --force
    git push --tags

    # Actually create the opus preview release and upload the cross-platform Jar
    echo "Creating opus preview release"
    gh release create latest-opus-preview "$uberjar_name#Cross-platform Jar" --prerelease \
       --title "Opus preview release being built" \
       --notes ":construction: This release is currently being built by GitHub Actions. Come back in a few minutes."

else

    echo "Should never get here: the opus branch should only build snapshots!"
    exit 1

fi
