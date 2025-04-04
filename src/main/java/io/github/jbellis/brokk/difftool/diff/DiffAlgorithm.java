
package io.github.jbellis.brokk.difftool.diff;


import io.github.jbellis.brokk.difftool.objects.Revision;

/**
 * A simple interface for implementations of differencing algorithms.
 */
public interface DiffAlgorithm {
    Revision diff(Object[] orig, Object[] rev);
}
