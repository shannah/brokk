package io.github.jbellis.brokk

import java.io.IOException;
import java.nio.file.Path
import java.nio.file.Files

/**
 * Abstraction for a filename relative to the repo.  This exists to make it less difficult to ensure
 * that different filename objects can be meaningfully compared, unlike bare Paths which may
 * or may not be absolute, or may be relative to the jvm root rather than the repo root.
 */
class RepoFile(@transient private var root: Path, @transient private var relPath: Path) extends BrokkFile {
  private val serialVersionUID = 1L
  
  def this(root: Path, relName: String) = this(root, Path.of(relName))

  // We can't rely on these being set until after deserialization
  if (root != null && relPath != null) {
    require(root.isAbsolute)
    require(root == root.normalize())
    require(!relPath.isAbsolute)
  }

  def absPath(): Path = root.resolve(relPath)

  @throws[IOException]
  def create(): Unit = {
    Files.createDirectories(absPath().getParent)
    Files.createFile(absPath())
  }

  @throws[IOException]
  def write(st: String): Unit = {
    Files.createDirectories(absPath().getParent)
    Files.writeString(absPath(), st)
  }

  /**
   * Also relative
   */
  def getParent: String = {
    Option(relPath.getParent).map(_.toString).getOrElse("")
  }

  override def toString: String = relPath.toString

  override def equals(o: Any): Boolean = o match {
    case repoFile: RepoFile => root == repoFile.root && relPath == repoFile.relPath
    case _ => false
  }

  override def hashCode: Int = relPath.hashCode
  
  @throws[IOException]
  @throws[java.io.ObjectStreamException]
  private def writeObject(oos: java.io.ObjectOutputStream): Unit = {
    oos.defaultWriteObject()
    // store the string forms of root/relPath
    oos.writeUTF(root.toString)
    oos.writeUTF(relPath.toString)
  }

  @throws[IOException]
  @throws[ClassNotFoundException]
  private def readObject(ois: java.io.ObjectInputStream): Unit = {
    // read all non-transient fields
    ois.defaultReadObject()
    // reconstitute root/relPath from the strings
    val rootString = ois.readUTF()
    val relString = ois.readUTF()
    // both must be absolute/relative as before
    root = Path.of(rootString)
    require(root.isAbsolute)

    relPath = Path.of(relString)
    require(!relPath.isAbsolute)
  }
}
