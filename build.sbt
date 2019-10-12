name := "brt4cats"

version := "0.1"

scalaVersion := "2.12.10"

addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.1")
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
scalacOptions += "-Ypartial-unification"

libraryDependencies += "org.typelevel" %% "cats-effect" % "2.0.0"
libraryDependencies += "org.typelevel" %% "cats-core" % "2.0.0"