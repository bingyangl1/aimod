package com.aimod.ai.pathing;

import java.util.Arrays;

/**
 * Binary heap open set for A* pathfinding.
 * Adapted from Baritone's BinaryHeapOpenSet (LGPL-3.0).
 * 
 * O(log n) insert, O(log n) extract-min, O(log n) decrease-key (update).
 * Uses heapPosition on PathNode for O(1) node lookup within the heap.
 */
public final class BinaryHeapOpenSet {
    private static final int INITIAL_CAPACITY = 1024;
    private PathNode[] array;
    private int size;

    public BinaryHeapOpenSet() {
        this(INITIAL_CAPACITY);
    }

    public BinaryHeapOpenSet(int size) {
        this.size = 0;
        this.array = new PathNode[size];
    }

    public int size() {
        return size;
    }

    public void insert(PathNode value) {
        if (size >= array.length - 1) {
            array = Arrays.copyOf(array, array.length << 1);
        }
        size++;
        value.heapPosition = size;
        array[size] = value;
        update(value);
    }

    public void update(PathNode val) {
        int index = val.heapPosition;
        int parentInd = index >>> 1;
        double cost = val.combinedCost;
        PathNode parentNode = array[parentInd];
        while (index > 1 && parentNode.combinedCost > cost) {
            array[index] = parentNode;
            array[parentInd] = val;
            val.heapPosition = parentInd;
            parentNode.heapPosition = index;
            index = parentInd;
            parentInd = index >>> 1;
            parentNode = array[parentInd];
        }
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public PathNode removeLowest() {
        if (size == 0) {
            throw new IllegalStateException("Cannot remove from empty heap");
        }
        PathNode result = array[1];
        PathNode val = array[size];
        array[1] = val;
        val.heapPosition = 1;
        array[size] = null;
        size--;
        result.heapPosition = -1;
        if (size < 2) {
            return result;
        }
        int index = 1;
        int smallerChild = 2;
        double cost = val.combinedCost;
        do {
            PathNode smallerChildNode = array[smallerChild];
            double smallerChildCost = smallerChildNode.combinedCost;
            if (smallerChild < size) {
                PathNode rightChildNode = array[smallerChild + 1];
                double rightChildCost = rightChildNode.combinedCost;
                if (smallerChildCost > rightChildCost) {
                    smallerChild++;
                    smallerChildCost = rightChildCost;
                    smallerChildNode = rightChildNode;
                }
            }
            if (cost <= smallerChildCost) {
                break;
            }
            array[index] = smallerChildNode;
            array[smallerChild] = val;
            val.heapPosition = smallerChild;
            smallerChildNode.heapPosition = index;
            index = smallerChild;
        } while ((smallerChild <<= 1) <= size);
        return result;
    }
}