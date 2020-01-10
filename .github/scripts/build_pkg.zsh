if [ ! -d Runtime ]; then
    curl --location https://corretto.aws/downloads/latest/amazon-corretto-11-x64-macos-jdk.tar.gz \
         --output runtime.tar.gz
    tar xvf runtime.tar.gz
    amazon-corretto-11.jdk/Contents/Home/bin/jlink --no-header-files --no-man-pages --compress=2 --strip-debug \
        --add-modules=java.base,java.desktop,java.management,java.naming,java.prefs,java.sql,jdk.zipfs,jdk.unsupported \
        --output Runtime
fi

mkdir Input
mv beat-link-trigger.jar Input

security create-keychain -p "$IDENTITY_PASSPHRASE" build.keychain
security default-keychain -s build.keychain
security unlock-keychain -p "$IDENTITY_PASSPHRASE" build.keychain

echo "$IDENTITY_P12_B64" > DS_ID_App.p12.txt
openssl base64 -d -in DS_ID_App.p12.txt -out DS_ID_App.p12
security import DS_ID_App.p12 -A -P "$IDENTITY_PASSPHRASE"

security set-key-partition-list -S apple-tool:,apple: -s -k "$IDENTITY_PASSPHRASE" build.keychain

jpackage --name "Beat Link Trigger" --input Input --runtime-image Runtime \
         --icon .github/resources/BeatLink.icns --main-jar beat-link-trigger.jar \
         --description "Trigger events and automate shows in response to events on Pioneer CDJs" \
         --copyright "© 2016-2020 Deep Symmetry, LLC" --vendor "Deep Symmetry, LLC" \
         --type dmg --mac-package-identifier "org.deepsymmetry.beat-link-trigger" \
         --mac-sign --mac-signing-key-user-name "Deep Symmetry, LLC (9M6LKU948Y)" \
         --app-version $version_tag

mv "$dmg_name" "$artifact_name"
