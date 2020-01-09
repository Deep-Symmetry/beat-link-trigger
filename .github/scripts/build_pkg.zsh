if [ ! -f Runtime ]; then
    curl --location https://corretto.aws/downloads/latest/amazon-corretto-11-x64-macos-jdk.tar.gz \
         --output runtime.tar.gz
    tar xvf runtime.tar.gz
    amazon-corretto-11.jdk/Contents/Home/bin/jlink --no-header-files --no-man-pages --compress=2 --strip-debug \
        --add-modules=java.base,java.desktop,java.management,java.naming,java.prefs,java.sql,jdk.zipfs,jdk.unsupported \
        --output Runtime
fi

if [ ! -f Input ]; then
    mkdir Input
fi

mv beat-link-trigger.jar Input
rm *.dmg

jpackage --name "Beat Link Trigger" --input Input --runtime-image Runtime \
         --icon .github/resources/BeatLink.icns --main-jar beat-link-trigger.jar \
         --description "Trigger events and automate shows in response to events on Pioneer CDJs" \
         --copyright "© 2016-2020 Deep Symmetry, LLC" --vendor "Deep Symmetry, LLC" \
         --type dmg --mac-package-identifier "org.deepsymmetry.beat-link-trigger" \
         --app-version $version_tag

mv "$env:msi_name" "$env:artifact_name"
