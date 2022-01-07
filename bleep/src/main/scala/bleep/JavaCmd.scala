package bleep

object JavaCmd {
  val javacommand = if (sys.props("os.name").toLowerCase.contains("win")) "java" else "/usr/bin/java"

}
