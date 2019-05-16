# User Guide Module

> :mag_right: If you are looking for the online user guide, it has
> [moved](https://blt-guide.deepsymmetry.org/) off of
> GitHub to become easier to read and navigate.

Beat Link Trigger now uses [Antora](https://antora.org) to build its
User Guide. This folder hosts the documentation module and playbooks
used to build it. `embedded.yml` is used to create the self-hosted
version which is served out of Beat Link Trigger itself, so it can be
used even without an Internet connection, and `netlify.yml` is used to
build the [online version](https://blt-guide.deepsymmetry.org/) that
is managed by [netlify](https://www.netlify.com).

The Leiningen project in the root of this repository automatically
invokes Antora to build the embedded version as an early build step.

The online version, which will grow to support multiple released
versions of Beat Link Trigger, is built automatically by netlify
whenever changes are pushed to the relevant branches on GitHub.

An older workflow was to build the documentation site manually for
hosting on the Deep Symmetry web site by running the following
commands from the project root:

    antora --fetch doc/ds.yml
    rsync -avz doc/build/site/ slice:/var/www/ds/beatlink/trigger/guide
