package com.arcanebrigade.core;

import java.util.HashMap;
import java.util.Map;

/**
 * 空间哈希网格。把碰撞检测从 O(n^2) 降到近似 O(n)。
 *
 * 用法：每帧 beginFrame() -> 对所有待检测实体 insert() -> 对每个查询调用 query()。
 * cell 大小应约等于"最大实体直径"，太大会退化，太小会跨太多格。
 */
public final class SpatialHash {

    private final float cell;
    private final Map<Long, IntList> buckets = new HashMap<>(1024);
    /** 本帧被写入过的桶 key，下一帧只清理这些，避免遍历整个 map */
    private final long[] keyStash = new long[4096];
    private int stashSize;

    public SpatialHash(float cellSize) {
        this.cell = cellSize;
    }

    public void beginFrame() {
        for (int i = 0; i < stashSize; i++) {
            IntList l = buckets.get(keyStash[i]);
            if (l != null) {
                l.clear();
            }
        }
        stashSize = 0;
    }

    private static long key(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xffffffffL);
    }

    public void insert(float x, float y, int id) {
        int cx = (int) Math.floor(x / cell);
        int cy = (int) Math.floor(y / cell);
        long k = key(cx, cy);
        IntList l = buckets.get(k);
        if (l == null) {
            l = new IntList(16);
            buckets.put(k, l);
        }
        if (l.size() == 0 && stashSize < keyStash.length) {
            keyStash[stashSize++] = k;
        }
        l.add(id);
    }

    /**
     * 收集与圆 (x,y,r) 可能相交的所有实体 id 到 out。
     * out 会被清空后填充。查询期间不要修改本结构。
     */
    public void query(float x, float y, float r, IntList out) {
        out.clear();
        int minX = (int) Math.floor((x - r) / cell);
        int maxX = (int) Math.floor((x + r) / cell);
        int minY = (int) Math.floor((y - r) / cell);
        int maxY = (int) Math.floor((y + r) / cell);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                IntList l = buckets.get(key(cx, cy));
                if (l == null) {
                    continue;
                }
                for (int i = 0; i < l.size(); i++) {
                    out.add(l.get(i));
                }
            }
        }
    }

    /** 桶数量，用于诊断：如果持续增长说明有泄漏 */
    public int bucketCount() {
        return buckets.size();
    }
}
