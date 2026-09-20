# GutapK

Opens, edits, clones and signs Android packages. Linux desktop, shipped as a single
AppImage.

## Install

Download `GutapK-x86_64.AppImage` from the Releases page, then:

    chmod +x GutapK-x86_64.AppImage
    ./GutapK-x86_64.AppImage

Nothing is installed on the system. The Java runtime is inside the AppImage.

## Verify the download

Each release carries the SHA-256 of its AppImage:

    sha256sum -c GutapK-x86_64.AppImage.sha256

## Build

Requires a JDK 21, Gradle 9.5 or later, and network access to Maven Central.
There is no Gradle wrapper in this repository, on purpose: a wrapper ships a
binary jar that cannot be reviewed as text.

    gradle test
    gradle createDistributable
    bash packaging/make-appimage.sh

The result lands in `dist/`.

## Licence

GNU General Public License version 3 or later. See `LICENSE`.
