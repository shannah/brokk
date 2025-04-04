
package io.github.jbellis.brokk.difftool.diff;


public interface JMDiffAlgorithmIF {
    void checkMaxTime(boolean checkMaxTime);

    JMRevision diff(Object[] orig, Object[] rev);
}
