ThisBuild / scalaVersion     := "3.8.4"
ThisBuild / organization     := "io.github.edadma"
ThisBuild / organizationName := "edadma"
ThisBuild / version          := "0.0.1"
// suit pulls sdl3 0.2.4 transitively; we depend on sdl3 0.2.5 directly for its core audio API,
// which wins the eviction. Warn rather than error on the version bump.
ThisBuild / evictionErrorLevel := Level.Warn

lazy val asteroids = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "asteroids",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
    ),
    libraryDependencies ++= Seq(
      // suit is the toolkit — it owns the SDL3 window/input/present loop and draws through Cairo,
      // and pulls SDL3, Cairo and FreeType in transitively.
      "io.github.edadma" %%% "suit" % "0.0.5",
      // sdl3 directly, for its core audio API (the synthesised sound effects).
      "io.github.edadma" %%% "sdl3" % "0.2.5",
    ),
  )
