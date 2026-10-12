package com.fredoseep.algorithm.planning;

import java.util.Objects;

/** Main-thread retry guard. A changed request is immediately eligible; time uses a monotonic clock. */
public final class RetryBackoff<K> {
    private K failed;
    private int failures;
    private long nextRetry;
    public boolean allowed(K key,long nowMillis) { return !Objects.equals(failed,key) || nowMillis>=nextRetry; }
    public int failures(K key) { return Objects.equals(failed,key)?failures:0; }
    public int fail(K key,long nowMillis) {
        failures=Objects.equals(failed,key)?Math.min(30,failures+1):1;
        failed=key;
        long delay=Math.min(30000L,2000L<<Math.min(4,failures-1));
        nextRetry=nowMillis+delay;
        return failures;
    }
    public void clear() { failed=null;failures=0;nextRetry=0; }
}
