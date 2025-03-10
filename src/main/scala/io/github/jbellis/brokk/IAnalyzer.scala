package io.github.jbellis.brokk

import java.util

trait IAnalyzer {
  def getAllClasses: util.List[CodeUnit] = 
    throw new UnsupportedOperationException()
    
  def getMembersInClass(fqClass: String): util.List[CodeUnit] = 
    throw new UnsupportedOperationException()
    
  def getClassesInFile(file: RepoFile): util.Set[CodeUnit] = 
    throw new UnsupportedOperationException()
    
  def isClassInProject(className: String): Boolean = 
    throw new UnsupportedOperationException()
    
  def getPagerank(seedClassWeights: java.util.Map[String, java.lang.Double], k: Int, reversed: Boolean = false): java.util.List[(String, java.lang.Double)] = 
    throw new UnsupportedOperationException()
    
  def getSkeleton(className: String): Option[String] = 
    throw new UnsupportedOperationException()
    
  def getSkeletonHeader(className: String): Option[String] = 
    throw new UnsupportedOperationException()
    
  def pathOf(codeUnit: CodeUnit): Option[RepoFile] = 
    throw new UnsupportedOperationException()
    
  def getDefinitions(pattern: String): util.List[CodeUnit] = 
    throw new UnsupportedOperationException()
    
  def getUses(symbol: String): util.List[CodeUnit] = 
    throw new UnsupportedOperationException()
    
  def getMethodSource(methodName: String): Option[String] = 
    throw new UnsupportedOperationException()
    
  def getClassSource(className: String): java.lang.String = 
    throw new UnsupportedOperationException()
}
