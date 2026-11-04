plugins { `kotlin-dsl` }

dependencies {
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.kotlin.allopen)
  implementation(libs.kotlin.noarg)
  implementation(libs.spring.boot.gradle.plugin)
  implementation(libs.spotless.gradle.plugin)
  implementation(libs.nimbus.jose.jwt)
}
