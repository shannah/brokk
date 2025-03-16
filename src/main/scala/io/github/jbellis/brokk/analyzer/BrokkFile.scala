package io.github.jbellis.brokk.analyzer

import java.io.{IOException, Serializable}
import java.nio.file.{Files, Path}

trait BrokkFile extends Serializable {
  def absPath(): Path

  @throws[IOException]
  def read(): String = Files.readString(absPath())

  def exists(): Boolean = {
    Files.exists(absPath())
  }

  /**
   * Just the filename, no path at all
   */
  @scala.annotation.nowarn
  def getFileName: String = {
    absPath().getFileName.toString
  }

  def toString: String
}
