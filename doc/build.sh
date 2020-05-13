#!/usr/bin/env bash

# This script is run by Netlify continuous integration to build the
# Antora site hosting the user guide.

npm i @antora/cli antora-site-generator-lunr
DOCSEARCH_ENABLED=true DOCSEARCH_ENGINE=lunr DOCSEARCH_INDEX_VERSION=latest \
  $(npm bin)/antora --fetch --generator antora-site-generator-lunr doc/netlify.yml

curl https://htmltest.wjdp.uk | bash
bin/htmltest
