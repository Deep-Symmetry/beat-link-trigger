# Define Wix-Toolset
$Heat = "${env:Wix}\bin\heat.exe"
$Candle = "${env:Wix}\bin\candle.exe"
$Light = "${env:Wix}\bin\light.exe"

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

# Move the downloaded cross-platform executable Jar into an Input folder to be used in building the
# native app bundle.
mkdir Input
copy "$env:uberjar_name" Input/beat-link-trigger.jar

# Build the native application bundle and installer.
jpackage --name "$env:blt_name" --input .\Input --add-modules "$env:blt_java_modules" `
 --icon ".\.github\resources\BeatLink.ico" `
 --main-jar beat-link-trigger.jar `
 --java-options "-Djava.net.preferIPv4Stack=true" `
 --type app-image `
 --description "$env:blt_description" --copyright "$env:blt_copyright" --vendor "$env:blt_vendor" `
 --app-version "$env:build_version"

#Get the Wix-Toolset file for Beat Link Trigger
copy ".\.github\resources\MSI Template.wxs" ".\"

## Wix-Toolset Party Time!
#Index all files in the Beat Link Trigger directory
& $Heat dir $env:blt_name -cg Application_Folder -dr App_Vendor_Folder -gg -ke -sfrag -sreg -template fragment -out "application_folder.wxs"

#Create Wix-Toolset Object file
& $Candle -dAppName=""$env:blt_name"" -dAppVersion=""$env:build_version"" -dAppVendor=""$env:blt_vendor"" -dAppUpgradeCode=""$env:blt_upgradecode"" -dAppDescription=""$env:blt_description"" -dAppVendorFolder=""$env:blt_vendor_folder"" -dAppIcon=""$env:blt_icon"" -nologo *.wxs -ext WixUIExtension -ext WixFirewallExtension -arch x64

#Compile MSI
#& $Light -b "Beat Link Trigger" -nologo "*.wixobj" -out ""$env:artifact_name"" -ext WixUIExtension -ext WixFirewallExtension
& $Light -b "Beat Link Trigger" -nologo "*.wixobj" -out "D:\a\beat-link-trigger\beat-link-trigger\Beat-Link-Trigger-7.4.0-Preview-Win64.msi" -ext WixUIExtension -ext WixFirewallExtension
