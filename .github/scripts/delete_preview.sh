#!/usr/bin/env bash

set -e  # Exit if any command fails.

# Check if the preview release exists.
if gh release view latest-preview --json assets --jq '.assets[].name' > preview_assets.txt ; then

    echo "There is a preview release. Deleting assets..."
    while read -r asset; do
        echo "  Deleting asset $asset:"
        gh release delete-asset latest-preview "$asset" --yes
    done <preview_assets.txt

    echo "Deleting the preview release itself:"
    gh release delete latest-preview --cleanup-tag --yes

else
    echo "No preview release found to clean up."
fi

rm -f preview_assets.txt
