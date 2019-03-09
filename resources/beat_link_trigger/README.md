## Placeholder File

This directory needs to exist for the build to work properly, so this
file is here to make sure Git creates the directory when you clone the
repo. (Git is not good about empty directories.)

Files in this directory will be made available on the class path at
runtime, and copied into the jar file when the project is built.

The [lein-v](https://github.com/roomkey/lein-v) plugin for Leiningen
creates a file called `version.edn` in this directory that contains
the version information of the build, as derived from the closest git
tag and commit history.
