If (! (Test-Path "Runtime")) {
    Invoke-WebRequest https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip `
      -OutFile jdk11.zip
    Expand-Archive .\jdk11.zip -DestinationPath .\jdk11
    $jdk11Subdir = (Get-ChildItem -Path jdk11 -name)
    $jlink = ".\jdk11\$jdk11Subdir\bin\jlink"
      & $jlink --no-header-files --no-man-pages --compress=2 --strip-debug `
        --add-modules=java.base,java.desktop,java.management,java.naming,java.prefs,java.sql,jdk.zipfs,jdk.unsupported `
        --output .\Runtime
}

If (! (Test-path "Input")) {
  mkdir Input
}

& "c:\Program Files\Java\openjdk-14-ea\bin\jpackage.exe" "--name" "Beat Link Trigger" `
 --input "C:\Users\James Elliott\Downloads\Input" `
 --runtime-image "C:\Users\James Elliott\Downloads\Runtime" `
 --icon "C:\Users\James Elliott\Downloads\BeatLink.ico" `
 --main-jar beat-link-trigger.jar `
 --win-menu --win-menu-group "Deep Symmetry" --type msi `
 --description "Trigger events and automate shows in response to events on Pioneer CDJs" `
 --copyright "© 2016-2020 Deep Symmetry, LLC" --vendor "Deep Symmetry, LLC" `
 --app-version 0.6.1
