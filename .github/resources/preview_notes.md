:construction: This is pre-release code for people who want to help test [what is going into the next release](https://github.com/Deep-Symmetry/beat-link-trigger/blob/main/CHANGELOG.md).

> Don’t download this if you aren’t comfortable testing code while it is under active development! Instead, look at the [latest release](https:///github.com/Deep-Symmetry/beat-link-trigger/releases/latest).

## What to Download

- If you already have a compatible Java runtime installed (Java 11 or later), you can just download the executable cross-platform Jar file, and should be able to run it by executing (in a shell window that is operating in the same directory you placed the downloaded the Jar file):
  java -jar _jar-file-name_
- If you are on a Mac, unfortunately something has broken down between OpenJDK and Apple which prevents code-signed, notarized disk images from being built successfully, so unless and until that can be resolved, you will need to use the cross-platform jar file as described above. You can install the Java distribution used by Deep Symmetry from [here](https://docs.aws.amazon.com/corretto/latest/corretto-17-ug/downloads-list.html). Be sure to choose the correct version for your processor, either [macOS aarch64 (for Apple Silicon)](https://corretto.aws/downloads/latest/amazon-corretto-17-aarch64-macos-jdk.pkg) or [macOS x64 (for Intel)](https://corretto.aws/downloads/latest/amazon-corretto-17-x64-macos-jdk.pkg).
- If you are on 64-bit Windows and don’t want to have to separately install Java you can download the Win64 MSI installer (`.msi` file), which installs a Windows application with the Java runtime built in.
