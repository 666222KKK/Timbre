plugins {
  id("voice.library")
  alias(libs.plugins.metro)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.data.api)
  implementation(projects.core.webdav)
  implementation(libs.serialization.json)
  implementation(libs.okhttp)
  testImplementation(libs.mockwebserver)
}
