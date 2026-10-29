package com.votry.productstudio.ml.sam

import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

object TargetMaskCleaner {
    fun keepLargestComponent(mask: TargetMask): TargetMask {
        val width = mask.width
        val height = mask.height
        val total = width * height
        val active = BooleanArray(total)
        for (i in 0 until total) active[i] = mask.probabilityAtIndex(i) >= 0.40f

        val visited = BooleanArray(total)
        var best = IntArray(0)
        val queue = ArrayDeque<Int>()

        for (start in 0 until total) {
            if (!active[start] || visited[start]) continue
            val current = ArrayList<Int>()
            visited[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                current.add(i)
                val x = i % width
                val y = i / width
                fun push(nx: Int, ny: Int) {
                    if (nx !in 0 until width || ny !in 0 until height) return
                    val j = ny * width + nx
                    if (!visited[j] && active[j]) {
                        visited[j] = true
                        queue.add(j)
                    }
                }
                push(x - 1, y)
                push(x + 1, y)
                push(x, y - 1)
                push(x, y + 1)
            }
            if (current.size > best.size) best = current.toIntArray()
        }

        if (best.isEmpty()) return mask

        val keep = BooleanArray(total)
        for (index in best) {
            val cx = index % width
            val cy = index / width
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x !in 0 until width || y !in 0 until height) continue
                    val j = y * width + x
                    if (mask.probabilityAtIndex(j) >= 0.08f) keep[j] = true
                }
            }
        }

        val cleaned = FloatArray(total)
        for (i in 0 until total) cleaned[i] = if (keep[i]) mask.logits[i] else -20f
        return TargetMask(cleaned, width, height, mask.score)
    }
}
