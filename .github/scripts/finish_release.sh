#!/usr/bin/env bash

set -e  # Exit if any command fails.

# See if we are creating a preview release
if [ "$release_snapshot" = true ] ; then

    # Update the release information on GitHub and reflect that it is ready.
    gh release edit latest-preview --title "$release_tag preview" --notes-file .github/resources/preview_notes.md

else

    # Look for the heading in the change log corresponding to this version
    pattern="^## \\[${git_version//\./\\.}\\]"
    if grep "$pattern" CHANGELOG.md >_header.md ; then

        # Extract the release date from that heading so we can build a link to the release notes.
        rel_date=`sed 's/.* - //' <_header.md`
        link="#${git_version//\./}---$rel_date"

        # Update the release note template to use the correct link
        sed "s/#LINK-GOES-HERE/$link/" .github/resources/release_notes.md > _release_notes.md

    else

        # Log a workflow warning reporting that this release could not be found in the change log.
        echo "::warning file=CHANGELOG.md,title=Unable to link from release notes.::No heading for release $git_version found"

        # Update the release note template to link to the top of the file
        sed "s/#LINK-GOES-HERE//" .github/resources/release_notes.md > _release_notes.md

    fi

    # Update the release information on GitHub and reflect that it is ready.
    gh release edit "$release_tag" --prerelease=false --latest --title "$release_tag" --notes-file _release_notes.md

    # Clean up our temporary files
    rm -f _header.md
    rm -f _release_notes.md

fi
