plugins {
  id("voice.library")
  id("voice.compose")
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.backup)
  implementation(projects.core.common)
  implementation(projects.core.webdav)
  implementation(projects.core.playback)
  implementation(projects.core.strings)
  implementation(projects.core.ui)
  implementation(projects.navigation)
  implementation(libs.navigation3.ui)
  implementation(libs.datastore)
  implementation(libs.androidxCore)
}
