# Known issues

## The release build has never been run

F-Droid builds the R8-shrunk release variant (`gradlew assembleRelease`); GitHub releases ship
the debug APK, so no user has run the release variant. It builds since 2026-10-29 (a JDK path
committed in `gradle.properties` and R8's missing `javax.el` classes stopped it), and
`app/proguard-rules.pro` keeps KICL and MBassador whole because both find `@Handler` methods
by reflection. Before submitting to F-Droid: install a release build (signed with any key) on
a device or emulator, connect to a network, join a channel, and check that messages, joins,
parts and nick changes come through. Once it has run, the keep rules can be narrowed to the
`@Handler` methods.

## No app icon

The manifest uses `@android:drawable/sym_def_app_icon`, Android's generic icon, so the launcher
and an F-Droid listing show that. Needs a launcher icon (an adaptive icon in `res/`) and, for
F-Droid, `fastlane/metadata/android/en-US/images/icon.png` at 512 px
(`D:\visual studio projects\F-Droid tools\render_store_icon.py` renders it from an adaptive
vector icon).

## No license notices for bundled libraries

The APK carries none of the license notices of the libraries it bundles (AndroidX, Kotlin and
Netty are Apache-2.0, KICL is MIT, which asks for its NOTICE files to go along with binary copies),
and the app has no licenses screen. Every published APK, GitHub's and F-Droid's once there, is
such a copy. The fix: collect the Gradle dependencies' licenses with the AboutLibraries plugin
(free software, no Google libraries) and show them on an "Open-source licenses" screen. Found
by the code review of the F-Droid preparation, 2026-10-29.
