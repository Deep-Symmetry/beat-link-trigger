# Define Wix-Toolset
$Heat = "${env:ProgramFiles(x86)}\WiX Toolset v3.11\bin\heat.exe"
$Candle = "${env:ProgramFiles(x86)}\WiX Toolset v3.11\bin\candle.exe"
$Light = "${env:ProgramFiles(x86)}\WiX Toolset v3.11\bin\light.exe"

# Check for Heat.exe
if (!(Test-Path $Heat)) {
  Write-Warning "Heat location not found, please check if Wix-Toolset is installed correctly"
}

# Check for Candle.exe
if (!(Test-Path $Candle)) {
  Write-Warning "Candle location not found, please check if Wix-Toolset is installed correctly"
}

# Check for Light.exe
if (!(Test-Path $Light)) {
  Write-Warning "Light location not found, please check if Wix-Toolset is installed correctly"
}

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
copy "$env:uberjar_name" Input/beat-link-trigger.jar

# Build the native application bundle and installer.
jpackage --name "$env:blt_name" --input .\Input --runtime-image .\Runtime `
 --icon ".\.github\resources\BeatLink.ico" `
 --main-jar beat-link-trigger.jar `
 --type app-image `
  --description "$env:blt_description" --copyright "$env:blt_copyright" --vendor "$env:blt_vendor" `
 --app-version "$env:build_version"

#Get the Wix-Toolset file for Beat Link Trigger
copy ".\.github\resources\Beat Link Trigger.wxs" ".\"

## Wix-Toolset Party Time!
#Index all files in the Beat Link Trigger directory
& $Heat dir "Beat Link Trigger" -cg BEAT_LINK_TRIGGER -dr DEEP_SYMMETRY -gg -ke -sfrag -sreg -template fragment -out beat_link_trigger.wxs

#Create Wix-Toolset Object file
& $Candle -dbltversion=""$env:build_version"" -nologo *.wxs -ext WixUIExtension

#Compile MSI
& $Light -b "Beat Link Trigger" -nologo "*.wixobj" -out  ""$env:artifact_name"" -ext WixUIExtension
