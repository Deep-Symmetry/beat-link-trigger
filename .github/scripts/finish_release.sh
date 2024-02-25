#!/usr/bin/env bash

set -e  # Exit if any command fails.

# See if we are creating a preview release
if [ "$release_snapshot" = true ] ; then

    gh release edit latest-preview --title "$release_tag preview" --notes-file .github/resources/preview_notes.md

else

    gh release edit "$release_tag" --prerelease=false --title "$release_tag" \
       --notes-file .github/resources/release_notes.md

fi
