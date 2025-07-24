#!/usr/bin/env bash

set -e  # Exit if any command fails.

# Set up node dependencies; probably redundant thanks to main workflow, but just in case...
npm install

# There is no point in doing this if we lack the SSH key to publish the guide.
if [ "$GUIDE_SSH_KEY" != "" ]; then

    # Build the cloud version of the documentation site and PDF artifact.
    echo "Building the online and PDF user guides."
    sudo apt-get update
    sudo apt-get install graphicsmagick
    sudo apt-get install libgraphicsmagick1-dev
    bundle config --local path .bundle/gems
    bundle

    if [ ! "$release_snapshot" = true ] ; then  # Build PDF from release branch?
        sed -i.bu "s/main/$git_version/" doc/antora-assembler.yml
    fi
    npm run hosted-docs -- --attribute "git-version=$git_version"

    # Make sure there are no broken links in the versions we care about.
    curl https://htmltest.wjdp.uk | bash
    bin/htmltest

    # Publish the user guide to the right place on the Deep Symmetry web server.
    rsync -avz doc/build/site/ guides@deepsymmetry.org:/var/www/guides/beat-link-trigger/

    # Rename the PDF to the name we like to use for the release artifact.
    mv doc/build/assembler-pdf/beat-link-trigger/main/_exports/index.pdf BLT-guide.pdf

    # Upload the PDF as a release artifact
    if [ "$release_snapshot" = true ] ; then
        gh release upload latest-preview "BLT-guide.pdf#User Guide (PDF)"
    else
        gh release upload "$release_tag" "BLT-guide.pdf#User Guide (PDF)"
    fi

else
    echo "No SSH key present, not building the user guide."
fi
