# Download and expand the Amazon Corretto 11 JDK, then use it to build the embedded JRE for inside
# the Mac application. But if it already exists (because we use a cache action to speed things up),
# we can skip this section.
If (! (Test-Path "Runtime")) {
    Invoke-WebRequest https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip `
      -OutFile jdk11.zip
    Expand-Archive .\jdk11.zip -DestinationPath .\jdk11
    $jdk11Subdir = (Get-ChildItem -Path jdk11 -name)
    $jlink = ".\jdk11\$jdk11Subdir\bin\jlink"
      & $jlink --no-header-files --no-man-pages --compress=2 --strip-debug `
        --add-modules="$env:blt_java_modules" --output .\Runtime
}

# Move the downloaded cross-platform executable Jar into an Input folder to be used in building the
# native app bundle.
mkdir Input
mv beat-link-trigger.jar Input

# Build the native application bundle and installer.
jpackage --name "$env:blt_name" --input .\Input --runtime-image .\Runtime `
 --icon ".\.github\resources\BeatLink.ico" `
 --main-jar beat-link-trigger.jar `
 --win-menu --win-menu-group "Deep Symmetry" --type msi `
 --win-upgrade-uuid 6D58C8D7-6163-43C6-93DC-A4C8CC1F81B6 `
 --description "$env:blt_description" --copyright "$env:blt_copyright" --vendor "$env:blt_vendor" `
 --app-version "$env:version_tag"

# Rename the installer file to the name we like to use for the release artifact.
mv "$env:msi_name" "$env:artifact_name"
