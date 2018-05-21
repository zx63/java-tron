package org.tron.common.utils;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

public class CycleBloomFilter {

  private BloomFilter<byte[]> currBloomFilter;
  private BloomFilter<byte[]> lastBloomFilter;
  private int tps;
  private int cycle;

  public CycleBloomFilter(int tps, int cycle) {
    this.tps = tps;
    this.cycle = cycle;

    currBloomFilter = getNewBloomFilter();
    lastBloomFilter = getNewBloomFilter();
  }

  private CycleBloomFilter() {
    int tps = 1000;
    // default 2 day
    int cycle = 2 * 24 * 60 * 60;
  }

  public synchronized boolean mightContain(byte[] k) {
    return currBloomFilter.mightContain(k) || lastBloomFilter.mightContain(k);
  }

  public synchronized void put(byte[] k) {
    currBloomFilter.put(k);
  }

  public synchronized void swich() {
    lastBloomFilter = currBloomFilter;
    currBloomFilter = getNewBloomFilter();
  }

  public static CycleBloomFilter create() {
    return new CycleBloomFilter();
  }

  private BloomFilter<byte[]> getNewBloomFilter() {
    int nTransEachDay = tps * cycle;
    return BloomFilter.create(Funnels.byteArrayFunnel(), nTransEachDay, 0.01);
  }

}
