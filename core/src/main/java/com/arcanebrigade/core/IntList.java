package com.arcanebrigade.core;

/**
 * 极简 int 动态数组。存在的唯一理由：避免每帧 new ArrayList / 装箱带来的 GC 压力。
 * clear() 只重置 size，保留底层数组容量。
 */
public final class IntList {

    private int[] data;
    private int size;

    public IntList() {
        this(64);
    }

    public IntList(int capacity) {
        data = new int[Math.max(8, capacity)];
    }

    public int size() {
        return size;
    }

    public int get(int i) {
        return data[i];
    }

    public void add(int v) {
        if (size == data.length) {
            int[] bigger = new int[data.length << 1];
            System.arraycopy(data, 0, bigger, 0, size);
            data = bigger;
        }
        data[size++] = v;
    }

    /** 覆盖指定下标的值。调用方保证 i 在 [0, size) 内 */
    public void set(int i, int v) {
        data[i] = v;
    }

    public void clear() {
        size = 0;
    }

    /** 线性查找。只用于很小的集合（连锁闪电的"已命中"列表最多几个元素） */
    public boolean contains(int v) {
        for (int i = 0; i < size; i++) {
            if (data[i] == v) {
                return true;
            }
        }
        return false;
    }
}
