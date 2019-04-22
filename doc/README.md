# User Guide Module

Beat Link Trigger now uses [Antora](https://antora.org) to build its
User Guide. This folder hosts the documentation module and playbooks
used to build it. `embedded.yml` is used to create the self-hosted
version which is served out of Beat Link Trigger itself, so it can be
used even without an Internet connection, and `ds.yml` is used to
build the [online
version](https://deepsymmetry.org/beatlink/trigger/guide/) that is
hosted on the Deep Symmetry web site.

The Leiningen project in the root of this repository automatically
invokes Antora to build the embedded version as an early build step.
The online version, which will grow to support multiple released
versions of Beat Link Trigger, is built manually by running the
following commands from the project root:

    antora --fetch doc/ds.yml
    rsync -avz doc/build/site/ slice:/var/www/ds/beatlink/trigger/guide
