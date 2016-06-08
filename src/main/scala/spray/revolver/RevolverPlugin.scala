/*
 * Copyright (C) 2009-2012 Johannes Rudolph and Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.revolver

import sbt._
import sbt.Keys._
import Actions._
import Utilities._
object RevolverPlugin extends AutoPlugin {

    object autoImport extends RevolverKeys {
      object Revolver {
        def settings = RevolverPlugin.settings

        def enableDebugging(port: Int = 5005, suspend: Boolean = false) =
          debugSettings in reStart := Some(DebugSettings(port, suspend))

        def noColors: Seq[String] = Nil
        def basicColors = Seq("BLUE", "MAGENTA", "CYAN", "YELLOW", "GREEN")
        def basicColorsAndUnderlined = basicColors ++ basicColors.map("_"+_)
      }

      val revolverSettings = RevolverPlugin.settings
    }
    import autoImport._

    lazy val settings = Seq(

      mainClass in reStart <<= mainClass in run in Compile,

      fullClasspath in reStart <<= fullClasspath in Runtime,

      reColors in Global in reStart := Revolver.basicColors,

      reStart <<= InputTask(startArgsParser) { args =>
        (streams, reLogTag, thisProjectRef, reForkOptions, mainClass in reStart, fullClasspath in reStart, reStartArgs, args)
          .map(restartApp)
          .dependsOn(products in Compile)
      },

      reStop <<= (streams, thisProjectRef).map(stopAppWithStreams),

      reStatus <<= (streams, thisProjectRef) map showStatus,

      // default: no arguments to the app
      reStartArgs in Global := Seq.empty,

      // initialize with env variable
      reJRebelJar in Global := Option(System.getenv("JREBEL_PATH")).getOrElse(""),

      debugSettings in Global := None,

      reLogTagUnscoped <<= thisProjectRef(_.project),

      // bake JRebel activation into java options for the forked JVM
      changeJavaOptionsWithExtra(debugSettings in reStart) { (jvmOptions, jrJar, debug) =>
        jvmOptions ++ createJRebelAgentOption(SysoutLogger, jrJar).toSeq ++
          debug.map(_.toCmdLineArg).toSeq
      },

      // bundles the various parameters for forking
      reForkOptions <<= (
        baseDirectory in reStart,
        javaOptions in reStart,
        outputStrategy,
        javaHome,
        envVars in reStart
      ) map { (base, jvmOptions, strategy, javaHomeDir, env) =>
        ForkOptions(
          javaHomeDir,
          strategy,
          Nil, // bootJars is empty by default because only jars on the user's classpath should be on the boot classpath
          workingDirectory = Some(base),
          runJVMOptions = jvmOptions,
          connectInput = false,
          envVars = env
        )
      },

      // stop a possibly running application if the project is reloaded and the state is reset
      onUnload in Global ~= { onUnload => state =>
        stopApps(colorLogger(state))
        onUnload(state)
      },

      onLoad in Global <<= (onLoad in Global, reColors in reStart) { (onLoad, colors) => state =>
        val colorTags = colors.map(_.toUpperCase formatted "[%s]")
        GlobalState.update(_.copy(colorPool = collection.immutable.Queue(colorTags: _*)))
        onLoad(state)
      }
    )

    override def requires = sbt.plugins.JvmPlugin
    override def trigger  = allRequirements
    override def projectSettings = settings

  /**
   * Changes javaOptions by using transformer function
   * (javaOptions, jrebelJarPath) => newJavaOptions
   */
  def changeJavaOptions(f: (Seq[String], String) => Seq[String]): Setting[_] =
    changeJavaOptionsWithExtra(sbt.Keys.baseDirectory /* just an ignored dummy */)((jvmArgs, path, _) => f(jvmArgs, path))

  def changeJavaOptionsWithExtra[T](extra: SettingKey[T])(f: (Seq[String], String, T) => Seq[String]): Setting[_] =
    javaOptions in reStart <<= (javaOptions, reJRebelJar, extra) map f
}
