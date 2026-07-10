#!/bin/bash
# Build the Kotlin framework for iOS
# Called by Xcode as a build phase
cd "$SRCROOT/.."
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
