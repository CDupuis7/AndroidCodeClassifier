package com.example.androidcodeclassifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

//Node and Leaf set up
private sealed class RfNode {
    data class Split(
        val featureIdx: Int,
        val threshold: Float,
        val left: RfNode,
        val right: RfNode
    ) : RfNode()

    data class Leaf(
        val probs: FloatArray
    ) : RfNode()
}

//RF model class
class RfJsonForest private constructor(
    val nEstimators: Int,
    val nClasses: Int,
    private val featureIndex: Map<String, Int>, // feature name -> column index
    private val trees: List<RfNode>
) {
    companion object {
        /** Load and parse rf_model.json from assets (org.json, no extra deps). */
        fun loadFromAssets(context: Context, assetName: String = "rf_model.json"): RfJsonForest {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val root = JSONObject(text)

            //Feature name index
            val names = root.getJSONArray("feature_names")
            val fnameToIdx = HashMap<String, Int>(names.length())
            for (i in 0 until names.length()) {
                fnameToIdx[names.getString(i)] = i
            }

            //Tree parsing
            val treesJA = root.getJSONArray("trees")
            val trees = ArrayList<RfNode>(treesJA.length())
            var detectedClasses = -1

            fun parseLeaf(value: Any): RfNode.Leaf {
                // value may be [[c0, c1]] or [c0, c1]; normalize to probabilities
                val arr: JSONArray = when (value) {
                    is JSONArray -> value
                    else -> JSONArray(value.toString())
                }
                val inner = if (arr.length() > 0 && arr.get(0) is JSONArray) arr.getJSONArray(0) else arr
                val probs = FloatArray(inner.length())
                var sum = 0.0
                for (i in 0 until inner.length()) {
                    val v = inner.getDouble(i)
                    probs[i] = v.toFloat()
                    sum += v
                }
                if (sum > 0.0) {
                    for (i in probs.indices) probs[i] = (probs[i] / sum.toFloat())
                } else {
                    // edge case: zero counts -> uniform
                    val p = 1f / probs.size
                    for (i in probs.indices) probs[i] = p
                }
                detectedClasses = maxOf(detectedClasses, probs.size)
                return RfNode.Leaf(probs)
            }

            fun parseNode(node: JSONObject): RfNode {
                return if (node.has("value")) {
                    parseLeaf(node.get("value"))
                } else {
                    val fname = node.getString("feature")
                    val fidx = fnameToIdx[fname] ?: -1
                    val thr = node.getDouble("threshold").toFloat()
                    val left = parseNode(node.getJSONObject("left"))
                    val right = parseNode(node.getJSONObject("right"))
                    RfNode.Split(fidx, thr, left, right)
                }
            }

            for (i in 0 until treesJA.length()) {
                trees += parseNode(treesJA.getJSONObject(i))
            }

            val nEst = root.optInt("n_estimators", trees.size)
            val nCls = if (detectedClasses > 0) detectedClasses else 2

            return RfJsonForest(
                nEstimators = nEst,
                nClasses = nCls,
                featureIndex = fnameToIdx,
                trees = trees
            )
        }
    }


    class X(private val map: Map<Int, Float>) {
        fun get(i: Int): Float = map[i] ?: 0f
    }

    //Encode features into the tree
    fun featurize(tokens: List<String>, methodCount: Int, opcodeCount: Int): X {
        val counts = HashMap<Int, Float>(tokens.size + 4)
        for (t in tokens) {
            val idx = featureIndex[t] ?: continue
            counts[idx] = (counts[idx] ?: 0f) + 1f
        }
        featureIndex["method_count"]?.let { counts[it] = methodCount.toFloat() }
        featureIndex["opcode_count"]?.let { counts[it] = opcodeCount.toFloat() }
        return X(counts)
    }

    //Evaluate a single tree
    private fun evalTree(root: RfNode, x: X): FloatArray {
        var cur = root
        while (true) {
            when (cur) {
                is RfNode.Leaf -> return cur.probs
                is RfNode.Split -> {
                    val v = x.get(cur.featureIdx)
                    cur = if (v <= cur.threshold) cur.left else cur.right
                }
            }
        }
    }

    //Sum of normalized leaf votes
    fun predict(x: X): Pair<Int, FloatArray> {
        val acc = FloatArray(nClasses)
        for (t in trees) {
            val p = evalTree(t, x)
            for (i in 0 until nClasses) acc[i] += p[i]
        }
        val s = acc.sum().takeIf { it > 0f } ?: 1f
        for (i in acc.indices) acc[i] /= s
        var best = 0; var bestVal = acc[0]
        for (i in 1 until acc.size) if (acc[i] > bestVal) { best = i; bestVal = acc[i] }
        return best to acc
    }
}
