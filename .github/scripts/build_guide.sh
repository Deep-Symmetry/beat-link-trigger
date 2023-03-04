#!/usr/bin/env bash

set -e  # Exit if any command fails.

# There is no point in doing this if we lack the SSH key to publish the guide.
if [ "$GUIDE_SSH_KEY" != "" ]; then

    # Set up node dependencies; probably redundant thanks to main workflow, but just in case...
    npm install

    # Build the cloud version of the documentation site.
    npm run hosted-docs

    # Make sure there are no broken links in the versions we care about.
    curl https://htmltest.wjdp.uk | bash
    bin/htmltest

    # Publish the user guide to the right place on the Deep Symmetry web server.
    rsync -avz doc/build/site/ guides@deepsymmetry.org:/var/www/guides/beat-link-trigger/

else
    echo "No SSH key present, not building user guide."
fi
