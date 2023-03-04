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

`embedded.yml` is used to create the self-hosted version which is
served out of Beat Link Trigger itself, so it can be used even without
an Internet connection, and `github-actions.yml` is used to build the
[online version](https://blt-guide.deepsymmetry.org/) that is hosted
on [deepsymmetry.org](https://deepsymmetry.org).

The Leiningen project in the root of this repository automatically
invokes Antora to build the embedded version as an early build step,
via `npm run local-docs`.

The online version, which will supports multiple released versions of
Beat Link Trigger, is built automatically by Github Actions whenever
changes are pushed to the relevant branches. The build script can be
found within the project at `.github/scripts/build_guide.sh`.

And the publish directory is `doc/build/site`.
