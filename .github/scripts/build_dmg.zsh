# Fail if any step fails
set -e

# Download the executable jar into an Input folder to be used in building the native app bundle
mkdir Input
if [ "$release_snapshot" = true ] ; then
    gh release download latest-preview --pattern "*.jar" --output Input/beat-link-trigger.jar
else
    gh release download "$release_tag" --pattern "*.jar" --output Input/beat-link-trigger.jar
fi

# See if the secrets needed to code-sign the native application are present.
if  [ "$IDENTITY_PASSPHRASE" != "" ]; then

    # We have secrets! Set up a keychain to hold the signing certificate. We can use the same
    # secret passphrase that we will use to import it as the keychain password, for simplicity.
    security create-keychain -p "$IDENTITY_PASSPHRASE" build.keychain
    security default-keychain -s build.keychain
    security unlock-keychain -p "$IDENTITY_PASSPHRASE" build.keychain

    # Put the base-64 encoded signing certificicate into a text file, decode it to binary form.
    echo "$IDENTITY_P12_B64" > DS_ID_App.p12.txt
    base64 --decode -i DS_ID_App.p12.txt -o DS_ID_App.p12

    # Install the decoded signing certificate into our unlocked build keychain.
    security import DS_ID_App.p12 -A -P "$IDENTITY_PASSPHRASE"

    # Set the keychain to allow use of the certificate without user interaction (we are headless!)
    security set-key-partition-list -S apple-tool:,apple: -s -k "$IDENTITY_PASSPHRASE" build.keychain

    # Explode the jar so we can fix code signatures on the problematic executables we embed.
    mkdir jar_tmp
    cd jar_tmp
    jar xf ../Input/beat-link-trigger.jar
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" libnrepl-macos-universal.so
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" META-INF/native/libnetty_transport_native_kqueue_x86_64.jnilib
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" com/sun/jna/darwin-aarch64/libjnidispatch.jnilib
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" com/sun/jna/darwin-x86-64/libjnidispatch.jnilib
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" --force uk/co/xfactorylibrarians/coremidi4j/libCoreMidi4J.dylib
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" --force org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" --force org/sqlite/native/Mac/x86_64/libsqlitejdbc.dylib
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" --force com/formdev/flatlaf/natives/libflatlaf-macos-x86_64.dylib
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" --force com/formdev/flatlaf/natives/libflatlaf-macos-arm64.dylib

    # Replace the jar with one containing the executables with corrected signatures.
    rm -f ../Input/beat-link-trigger.jar
    jar cfm ../Input/beat-link-trigger.jar META-INF/MANIFEST.MF .
    cd ..
    rm -rf jar_tmp

    # Run jpackage to build the native application as a code signed disk image
    jpackage --name "$blt_name" --input Input --add-modules "$blt_java_modules" \
             --icon .github/resources/BeatLink.icns --main-jar beat-link-trigger.jar \
             --description "$blt_description" --copyright "$blt_copyright" --vendor "$blt_vendor" \
             --mac-package-identifier "org.deepsymmetry.beat-link-trigger" --app-version "$build_version" \
             --mac-sign --mac-package-signing-prefix "org.deepsymmetry.beat-link-trigger." \
             --mac-signing-key-user-name  "Deep Symmetry, LLC (9M6LKU948Y)" \
             --mac-entitlements  .github/resources/Clojure.entitlements

    # Code sign the outer package itself
    codesign --timestamp -s "Deep Symmetry, LLC (9M6LKU948Y)" "$dmg_name"

    # Try to verify the code signature of the package
    codesign -vvvv --deep --strict "$dmg_name"

    # Submit the disk image to Apple for notarization.
    echo "Sumbitting the disk image to Apple for notarization..."
    xcrun notarytool submit --apple-id "$blt_mac_notarization_user" --password "$NOTARIZATION_PW" \
          --team-id "$blt_mac_team_id" "$dmg_name" --wait

    # Staple the notarization ticket to the disk image.
    echo "Notarization succeeded, stapling receipt to disk image."
    xcrun stapler staple "$dmg_name"

else
    # We have no secrets, so build the native application disk image without code signing.
    jpackage --name "$blt_name" --input Input --add-modules "$blt_java_modules" \
             --icon .github/resources/BeatLink.icns --main-jar beat-link-trigger.jar \
             --description "$blt_description" --copyright "$blt_copyright" --vendor "$blt_vendor" \
             --mac-package-identifier "org.deepsymmetry.beat-link-trigger" --app-version "$build_version"
fi

# Rename the disk image to the name we like to use for the release artifact.
mv "$dmg_name" "$artifact_name"

# Upload the disk image as a release artifact
if [ "$release_snapshot" = true ] ; then
    gh release upload latest-preview "$artifact_name#$artifact_description"
else
    gh release upload "$release_tag" "$artifact_name#$artifact_description"
fi
