#!/usr/bin/env bash

set -e  # Exit if any command fails.

# Check if the opus preview release exists.
if gh release view latest-opus-preview --json assets --jq '.assets[].name' > preview_assets.txt ; then

    echo "There is an opus preview release. Deleting assets..."
    while read -r asset; do
        echo "  Deleting asset $asset:"
        gh release delete-asset latest-opus-preview "$asset" --yes
    done <preview_assets.txt

    echo "Deleting the opus preview release itself:"
    gh release delete latest-opus-preview --cleanup-tag --yes

else
    echo "No opus preview release found to clean up."
fi

rm -f preview_assets.txt
