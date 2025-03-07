package bleep
package commands

import bleep.BleepException
import bleep.bsp.BspCommandFailed
import bleep.logging.jsonEvents
import ch.epfl.scala.bsp4j
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError

import java.io.File
import java.util.concurrent.ExecutionException
import scala.build.bloop.BloopServer
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** @param raw
  *   use raw stdin and stdout and avoid the logger
  */
case class Run(
    started: Started,
    project: model.CrossProjectName,
    maybeOverriddenMain: Option[String],
    args: List[String],
    raw: Boolean
) extends BleepCommandRemote(started) {
  override def runWithServer(bloop: BloopServer): Either[BleepException, Unit] = {
    val maybeSpecifiedMain: Option[String] =
      maybeOverriddenMain.orElse(started.build.explodedProjects(project).platform.flatMap(_.mainClass))

    val maybeMain: Either[BleepException, String] =
      maybeSpecifiedMain match {
        case Some(mainClass) => Right(mainClass)
        case None =>
          started.logger.info("No main class specified in build or command line. discovering...")
          discoverMain(bloop, project)
      }

    maybeMain.flatMap { main =>
      // we could definitely run js/native projects in "raw" mode as well, it just needs to be implemented
      started.build.explodedProjects(project).platform.flatMap(_.name) match {
        case Some(model.PlatformId.Jvm) | None if raw =>
          rawRun(bloop, main)
        case _ =>
          bspRun(bloop, main)
      }
    }
  }

  def rawRun(bloop: BloopServer, main: String): Either[BleepException, Unit] =
    Compile(started, Array(project)).runWithServer(bloop).map { case () =>
      val bloopProject = started.bloopProjects(project)
      val cp = fixedClasspath(bloopProject)
      cli(
        "run",
        started.prebootstrapped.buildPaths.cwd,
        List[List[String]](
          List(started.jvmCommand.toString),
          bloopProject.java match {
            case Some(java) => java.options
            case None       => Nil
          },
          List("-classpath", cp.mkString(File.pathSeparator), main),
          args
        ).flatten,
        logger = started.logger,
        out = cli.Out.Raw,
        in = cli.In.Attach,
        env = sys.env.toList
      )
    }

  def bspRun(bloop: BloopServer, main: String): Either[BspCommandFailed, Unit] = {
    val params = new bsp4j.RunParams(buildTarget(started.buildPaths, project))
    val mainClass = new bsp4j.ScalaMainClass(main, args.asJava, List(s"-Duser.dir=${started.prebootstrapped.buildPaths.cwd}").asJava)
    val envs = sys.env.updated(jsonEvents.CallerProcessAcceptsJsonEvents, "true").map { case (k, v) => s"$k=$v" }.toList.sorted.asJava
    mainClass.setEnvironmentVariables(envs)
    params.setData(mainClass)
    params.setDataKind("scala-main-class")
    started.logger.debug(params.toString)

    def failed(reason: BspCommandFailed.Reason) =
      Left(new BspCommandFailed("Run", Array(project), reason))

    Try(bloop.server.buildTargetRun(params).get().getStatusCode) match {
      case Success(bsp4j.StatusCode.OK) => Right(started.logger.info("Run succeeded"))
      case Success(errorCode)           => failed(BspCommandFailed.StatusCode(errorCode))
      case Failure(exception) =>
        def findResponseError(th: Throwable): Option[ResponseError] =
          th match {
            case x: ExecutionException =>
              findResponseError(x.getCause)
            case x: ResponseErrorException =>
              Option(x.getResponseError)
            case _ => None
          }

        findResponseError(exception) match {
          case Some(responseError) => failed(BspCommandFailed.FoundResponseError(responseError))
          case None                => failed(BspCommandFailed.FailedWithException(exception))
        }
    }
  }
}
