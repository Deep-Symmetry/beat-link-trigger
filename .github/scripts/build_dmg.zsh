# Download and expand the Oracle OpenJDK 14, which is properly notarized.
# But if it already exists (because we use a cache action to speed things up), we can skip this section.
if [ ! -d jdk-14.0.1.jdk ]; then
    echo "Downloding and extracting OpenJDK 14.0.1."
    curl --location \
         https://download.java.net/java/GA/jdk14.0.1/664493ef4a6946b186ff29eb326336a2/7/GPL/openjdk-14.0.1_osx-x64_bin.tar.gz \
         --output runtime.tar.gz
    tar xvf runtime.tar.gz
fi

# Use OpenJDK 14 to build the embedded JRE for inside the Mac application.
echo "Creating optimized OpenJDK runtime for embedding into Beat Link Trigger."
jdk-14.0.1.jdk/Contents/Home/bin/jlink --no-header-files --no-man-pages --compress=2 --strip-debug \
  --add-modules="$blt_java_modules" --output Runtime

# Move the downloaded cross-platform executable Jar into an Input folder to be used in building the
# native app bundle.
mkdir Input
mv $uberjar_name Input/beat-link-trigger.jar

# See if the secrets needed to code-sign the native application are present.
if  [ "$IDENTITY_PASSPHRASE" != "" ]; then

    # We have secrets! Set up a keychain to hold the signing certificate. We can use the same
    # secret passphrase that we will use to import it as the keychain password, for simplicity.
    security create-keychain -p "$IDENTITY_PASSPHRASE" build.keychain
    security default-keychain -s build.keychain
    security unlock-keychain -p "$IDENTITY_PASSPHRASE" build.keychain

    # Put the base-64 encoded signing certificicate into a text file, decode it to binary form.
    echo "$IDENTITY_P12_B64" > DS_ID_App.p12.txt
    openssl base64 -d -in DS_ID_App.p12.txt -out DS_ID_App.p12

    # Install the decoded signing certificate into our unlocked build keychain.
    security import DS_ID_App.p12 -A -P "$IDENTITY_PASSPHRASE"

    # Set the keychain to allow use of the certificate without user interaction (we are headless!)
    security set-key-partition-list -S apple-tool:,apple: -s -k "$IDENTITY_PASSPHRASE" build.keychain

    # Run jpackage to build the native application as an application image so we can fix code signing issues.
    jdk-14.0.1.jdk/Contents/Home/bin/jpackage --name $blt_name --input Input --runtime-image Runtime \
             --icon .github/resources/BeatLink.icns --main-jar beat-link-trigger.jar \
             --description $blt_description --copyright $blt_copyright --vendor $blt_vendor \
             --type app-image --mac-package-identifier "org.deepsymmetry.beat-link-trigger" \
             --app-version $build_version

    # Remove the extra copy of libjli.dylib which causes notarization to fail
    rm -r "$blt_name.app/Contents/runtime/Contents/MacOS/"

    # Code sign the application image more robustly than jpackage is currently able to, for Catalina.
    echo "Code signing the application image."
    codesign --force --timestamp --options runtime \
             --verbose=4 --entitlements .github/resources/Clojure.entitlements \
             --prefix "org.deepsymmetry.beat-link-trigger." \
             --sign "$blt_mac_signing_name" "$blt_name.app"

    # Create a disk image that contains just the application image.
    echo "Creating the disk image."
    mkdir Output
    mv "$blt_name.app" Output
    hdiutil create -volname "$blt_name" -fs HFS+ -srcfolder Output/ -ov -format UDZO "$dmg_name"

    # Code sign the disk image as well.
    echo "Code signing the disk image."
    codesign --timestamp --options runtime \
             --verbose=4 --entitlements .github/resources/Clojure.entitlements \
             --prefix "org.deepsymmetry.beat-link-trigger." \
             --sign "$blt_mac_signing_name" "$dmg_name"

    # Submit the disk image to Apple for notarization.
    echo "Sumbitting the disk image to Apple for notarization..."
    xcrun altool --notarize-app --primary-bundle-id "org.deepsymmetry.beat-link-trigger" \
          --username "$blt_mac_notarization_user" --password "$NOTARIZATION_PW" \
          --file "$dmg_name" --output-format xml > upload_result.plist
    request_id=`/usr/libexec/PlistBuddy -c "Print :notarization-upload:RequestUUID" upload_result.plist`

    # Wait until the request is done processing.
    while true; do
        sleep 60
        xcrun altool --notarization-info $request_id \
              --username "$blt_mac_notarization_user" --password "$NOTARIZATION_PW" \
              --output-format xml > status.plist
        if [ "`/usr/libexec/PlistBuddy -c "Print :notarization-info:Status" status.plist`" != "in progress" ]; then
            break;
        fi
        echo "...still waiting for notarization to finish..."
    done

    # See if notarization succeeded, and if so, staple the ticket to the disk image.
    if [ `/usr/libexec/PlistBuddy -c "Print :notarization-info:Status" status.plist` = "success" ]; then
        echo "Notarization succeeded, stapling receipt to disk image."
        xcrun stapler staple "$dmg_name"
    else
        false;
    fi

else
    # We have no secrets, so build the native application disk image without code signing.
    jpackage --name $blt_name --input Input --runtime-image Runtime \
             --icon .github/resources/BeatLink.icns --main-jar beat-link-trigger.jar \
             --description $blt_description --copyright $blt_copyright --vendor $blt_vendor \
             --type dmg --mac-package-identifier "org.deepsymmetry.beat-link-trigger" \
             --app-version $build_version
fi

# Rename the disk image to the name we like to use for the release artifact.
mv "$dmg_name" "$artifact_name"
