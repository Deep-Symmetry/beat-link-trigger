#!/usr/bin/env bash

set -e  # Exit if any command fails.

# See if we are creating a preview release
if [ "$release_snapshot" = true ] ; then

    # Create (or move) the latest-preview tag locally, then push it.
    echo "Creating tag for preview release"
    git config --global user.name 'James Elliott'
    git config --global user.email 'james@deepsymmetry.org'
    git tag latest-preview -m "The latest preview release" --force
    git push --tags

    # Actually create the preview release and upload the cross-platform Jar
    echo "Creating preview release"
    gh release create latest-preview "$uberjar_name#Cross-platform Jar" --prerelease \
       --title "Preview release being built" \
       --notes ":construction: This release is currently being built by GitHub Actions. Come back in a few minutes."

else

    # Actually create the release and upload the cross-platform Jar
    echo "Creating final release"
    gh release create "$release_tag" "$uberjar_name#Cross-platform Jar" --prerelease \
       --title "Release being built" \
       --notes ":construction: This release is currently being built by GitHub Actions. Come back in a few minutes."

fi
