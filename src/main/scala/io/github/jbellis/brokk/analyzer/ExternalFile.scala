package io.github.jbellis.brokk.analyzer

import java.nio.file.Path

/**
 * A BrokkFile that represents a file not relative to any repo, just specified by its absolute path.
 */
class ExternalFile(@transient private var path: Path) extends BrokkFile {
  private val serialVersionUID = 1L
  
  // Constructor validation
  if (path != null) {
    require(path.isAbsolute)
    require(path == path.normalize())
  }

  def absPath(): Path = path

  override def toString: String = path.toString
  
  @throws[java.io.IOException]
  @throws[java.io.ObjectStreamException]
  private def writeObject(oos: java.io.ObjectOutputStream): Unit = {
    oos.defaultWriteObject()
    oos.writeUTF(path.toString)
  }

  @throws[java.io.IOException]
  @throws[ClassNotFoundException]
  private def readObject(ois: java.io.ObjectInputStream): Unit = {
    ois.defaultReadObject()
    val pathStr = ois.readUTF()
    path = Path.of(pathStr)
    require(path.isAbsolute)
  }
}
