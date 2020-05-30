# User Guide Module

> :mag_right: If you are looking for the online user guide, it has
> [moved](https://blt-guide.deepsymmetry.org/) off of
> GitHub to become easier to read and navigate.

Beat Link Trigger now uses [Antora](https://antora.org) to build its
User Guide. This folder hosts the documentation module and playbooks
used to build it.

The Antora infrastructure is built on `npm`, so you will need `node`
installed, and to run `npm install` within the root directory of your
Beat Link Trigger clone before any builds will work.

`embedded.yml` is used to create the self-hosted
version which is served out of Beat Link Trigger itself, so it can be
used even without an Internet connection, and `netlify.yml` is used to
build the [online version](https://blt-guide.deepsymmetry.org/) that
is managed by [netlify](https://www.netlify.com).

The Leiningen project in the root of this repository automatically
invokes Antora to build the embedded version as an early build step,
via `npm run local-docs`.

The online version, which will supports multiple released versions of
Beat Link Trigger, is built automatically by netlify whenever changes
are pushed to the relevant branches on GitHub. The netlify build
command is `npm run netlify-docs`.

And the publish directory is `doc/build/site`.

An older workflow was to build the documentation site manually for
hosting on the Deep Symmetry web site by running the following
commands from the project root (this also predated the integration of
lunr search features, and the management of local antora versions
using `package.json`):

    antora --fetch doc/ds.yml
    rsync -avz doc/build/site/ slice:/var/www/ds/beatlink/trigger/guide
