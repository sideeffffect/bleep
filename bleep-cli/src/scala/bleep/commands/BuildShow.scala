package bleep
package commands

import bleep.BleepException
import bloop.config.ConfigCodecs
import cats.data.NonEmptyList

object BuildShow {
  case class Short(started: Started, projects: NonEmptyList[model.ProjectName]) extends BleepCommand {
    override def run(): Either[BleepException, Unit] = {
      val build = started.build.requireFileBacked("command build show short")
      projects.toList.foreach { projectName =>
        val p = build.file.projects.value(projectName)
        println(fansi.Color.Red(projectName.value))
        println(yaml.encodeShortened(p))
      }

      Right(())
    }
  }
  case class Exploded(started: Started, projects: Array[model.CrossProjectName]) extends BleepCommand {
    override def run(): Either[BleepException, Unit] = {
      projects.foreach { crossProjectName =>
        val p0 = started.build.explodedProjects(crossProjectName)
        // we don't currently do these cleanups to be able to go back to short version
        val p = p0.copy(cross = model.JsonMap.empty, `extends` = model.JsonSet.empty)
        println(fansi.Color.Red(crossProjectName.value))
        println(yaml.encodeShortened(p))
      }

      Right(())
    }
  }

  case class Bloop(started: Started, projects: Array[model.CrossProjectName]) extends BleepCommand {
    override def run(): Either[BleepException, Unit] = {
      projects.foreach { crossProjectName =>
        val f = started.bloopFiles(crossProjectName).forceGet
        println(fansi.Color.Red(crossProjectName.value))
        println(ConfigCodecs.toStr(f))
      }

      Right(())
    }
  }
}
