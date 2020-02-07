# Download and expand the Amazon Corretto 11 JDK, then use it to build the embedded JRE for inside
# the Mac application. But if it already exists (because we use a cache action to speed things up),
# we can skip this section.
if [ ! -d Runtime ]; then
    curl --location https://corretto.aws/downloads/latest/amazon-corretto-11-x64-macos-jdk.tar.gz \
         --output runtime.tar.gz
    tar xvf runtime.tar.gz
    amazon-corretto-11.jdk/Contents/Home/bin/jlink --no-header-files --no-man-pages --compress=2 --strip-debug \
        --add-modules="$blt_java_modules" --output Runtime
fi

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

    # Run jpackage to build the native application as a disk image.
    jpackage --name $blt_name --input Input --runtime-image Runtime \
             --icon .github/resources/BeatLink.icns --main-jar beat-link-trigger.jar \
             --description $blt_description --copyright $blt_copyright --vendor $blt_vendor \
             --type dmg --mac-package-identifier "org.deepsymmetry.beat-link-trigger" \
             --app-version $build_version

    # Code sign the disk image more robustly than jpackage is currently able to, for Catalina.
    echo "Code signing the disk image."
    codesign --force --preserve-metadata=identifier,requirements --deep --timestamp --options runtime \
             --entitlements .github/resources/Clojure.entitlements \
             --prefix "org.deepsymmetry.beat-link-trigger." \
             --sign $blt_mac_signing_name "$dmg_name"

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
