package io.github.jbellis.brokk.analyzer;

/**
 * A record representing a call site in source code.
 * 
 * @param signature The signature of the method making the call
 * @param sourceLine The actual source line where the call occurs
 */
public record CallSite(String signature, String sourceLine) {}
