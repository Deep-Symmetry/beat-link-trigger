#!/usr/bin/env bash

set -e  # Exit if any command fails.

# See if we are creating a preview release
if [ "$release_snapshot" = true ] ; then

    # Update the release information on GitHub and reflect that it is ready.
    gh release edit latest-opus-preview --title "$release_tag preview" --notes-file .github/resources/opus_preview_notes.md

else

    echo "Should not get here: the opus branch should only build snapshots!"
    exit 1

fi
