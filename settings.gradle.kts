rootProject.name = "chromascape"

// Resolve canonical project dir to handle systems where /home is a symlink (e.g. Fedora /var/home)
// e.g. Fedora Silverblue, Kinoite, Bluefin, Bazzite, etc.
// Without this, Spotless compares /home/... against /var/home/... and fails.
rootProject.projectDir = rootProject.projectDir.canonicalFile
