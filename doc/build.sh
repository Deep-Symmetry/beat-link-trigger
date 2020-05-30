#!/usr/bin/env bash

# This script is run by Netlify continuous integration to build the
# Antora site hosting the user guide.

npm run netlify-docs

curl https://htmltest.wjdp.uk | bash
bin/htmltest
